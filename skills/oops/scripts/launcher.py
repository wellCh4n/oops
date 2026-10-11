#!/usr/bin/env python3
"""OOPS CLI launcher — Python 3.8+, stdlib only.

The server owns the command implementation. Only configuration and the update
protocol live here, so new server commands work without reinstalling the skill.
"""

import argparse
import hashlib
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CONFIG_PATH = Path.home() / ".oops" / "config.toml"
CACHE_ROOT = Path.home() / ".oops" / "cache" / "skill"
SCRIPT_PATH = "/openapi/skill/oops.py"
MAX_SCRIPT_BYTES = 8 * 1024 * 1024


def load_config():
    if not CONFIG_PATH.exists():
        return {}
    config = {}
    for line in CONFIG_PATH.read_text().splitlines():
        match = re.match(r'^(\w+)\s*=\s*"(.*)"', line)
        if match:
            config[match.group(1)] = match.group(2)
    return config


def local_auth(arguments, config):
    parser = argparse.ArgumentParser(prog="oops auth")
    commands = parser.add_subparsers(dest="command", required=True)
    configure = commands.add_parser("set")
    configure.add_argument("--endpoint", required=True)
    configure.add_argument("--token", required=True)
    commands.add_parser("status")
    args = parser.parse_args(arguments)
    if args.command == "set":
        config.update(endpoint=args.endpoint.rstrip("/"), token=args.token)
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        atomic_write(CONFIG_PATH, ("\n".join(
            '{} = "{}"'.format(key, value) for key, value in config.items()
        ) + "\n").encode())
        print("Saved: endpoint={}".format(config["endpoint"]))
    else:
        endpoint = os.environ.get("OOPS_ENDPOINT") or config.get("endpoint") or "(not set)"
        token = os.environ.get("OOPS_TOKEN") or config.get("token")
        print("Endpoint: {}".format(endpoint))
        print("Token: {}".format(token[:8] + "..." if token else "(not set)"))


def atomic_write(path, content):
    # A unique temporary file + replace prevents concurrent invocations from
    # observing a partial download. mkstemp also keeps credentials/cache private.
    descriptor, temporary = tempfile.mkstemp(prefix=".oops-", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Never send the access token to a redirect target or execute its code.
        return None


def fetch(endpoint, token, path, limit):
    request = urllib.request.Request(endpoint + path, headers={
        "Authorization": "Bearer " + token,
        "Cache-Control": "no-cache",
    })
    opener = urllib.request.build_opener(NoRedirects())
    try:
        with opener.open(request, timeout=15) as response:
            content = response.read(limit + 1)
    except urllib.error.HTTPError as error:
        raise RuntimeError("script update {} returned HTTP {}".format(path, error.code)) from error
    except (urllib.error.URLError, OSError) as error:
        raise RuntimeError("cannot check/download server script: {}".format(error)) from error
    if len(content) > limit:
        raise RuntimeError("script update response exceeds size limit")
    return content


def server_script(endpoint, token):
    server_key = hashlib.sha256(endpoint.encode()).hexdigest()
    cache = CACHE_ROOT / server_key / "oops.py"
    # A rolling upgrade can change the version between the two requests. Retry
    # only the download protocol, never an application command.
    for _ in range(3):
        expected = fetch(endpoint, token, SCRIPT_PATH + "/hash", 128).decode("ascii").strip()
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise RuntimeError("server returned an invalid SHA-256 script hash")
        content = cache.read_bytes() if cache.exists() else None
        if content is not None and hashlib.sha256(content).hexdigest() == expected:
            return compile(content, str(cache), "exec"), cache
        content = fetch(endpoint, token, SCRIPT_PATH, MAX_SCRIPT_BYTES)
        if hashlib.sha256(content).hexdigest() != expected:
            continue
        code = compile(content, str(cache), "exec")
        cache.parent.mkdir(parents=True, exist_ok=True)
        atomic_write(cache, content)
        return code, cache
    raise RuntimeError("server script hash mismatch after 3 attempts; command was not executed")


def main():
    # Parse only the stable global prefix; all command-specific arguments,
    # including commands introduced by newer servers, belong to the server CLI.
    parser = argparse.ArgumentParser(prog="oops", add_help=False, allow_abbrev=False,
                                     description="OOPS CLI — commands are loaded from your server")
    parser.add_argument("--endpoint")
    parser.add_argument("--token")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("-h", "--help", action="store_true")
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        config = load_config()
        if args.arguments and args.arguments[0] == "auth":
            local_auth(args.arguments[1:], config)
            return
        endpoint = args.endpoint or os.environ.get("OOPS_ENDPOINT") or config.get("endpoint")
        token = args.token or os.environ.get("OOPS_TOKEN") or config.get("token")
        if (args.help or not sys.argv[1:]) and not (endpoint and token):
            parser.print_help()
            print("\nConfigure first: oops auth set --endpoint URL --token TOKEN")
            print("Then run oops --help for the server's current commands.")
            return
        if not endpoint or not token:
            raise RuntimeError("no endpoint/token configured — run `oops auth set --endpoint URL --token TOKEN`")
        endpoint = endpoint.rstrip("/")
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.query or parsed.fragment or parsed.username:
            raise RuntimeError("endpoint must be an HTTP(S) server URL without credentials, query or fragment")
        code, cache = server_script(endpoint, token)
    except (OSError, ValueError, RuntimeError, SyntaxError) as error:
        print("error: {}".format(error), file=sys.stderr)
        sys.exit(1)
    # Execute the exact bytes whose hash was checked, even if another process
    # updates the cache meanwhile. Keep argv/cwd/stdin/stdout and exit semantics.
    exec(code, {"__name__": "__main__", "__file__": str(cache), "__package__": None})


if __name__ == "__main__":
    main()
