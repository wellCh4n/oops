use anyhow::Result;
use clap::{Args, Subcommand};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum ServiceCommand {
    /// Show the service config.
    Get(GetArgs),
    /// Set port + per-environment hostnames.
    ///
    /// Pass --env-host repeatedly: `--env-host dev=foo.example.com:https`,
    /// where the third segment (https or http) is optional and defaults to
    /// http.
    Set(SetArgs),
}

#[derive(Debug, Args)]
pub struct GetArgs {
    #[arg(short = 'n', long)]
    namespace: String,
    name: String,
}

#[derive(Debug, Args)]
pub struct SetArgs {
    #[arg(short = 'n', long)]
    namespace: String,
    name: String,
    /// Container port the service exposes.
    #[arg(long)]
    port: i32,
    /// One or more env hostnames, format: env=host[:https|:http].
    #[arg(long = "env-host", value_parser = parse_env_host)]
    env_hosts: Vec<EnvHost>,
}

#[derive(Debug, Clone)]
pub struct EnvHost {
    env: String,
    host: String,
    https: bool,
}

fn parse_env_host(input: &str) -> Result<EnvHost, String> {
    let (env, rest) = input
        .split_once('=')
        .ok_or_else(|| "expected env=host[:https|:http]".to_string())?;
    let mut parts = rest.rsplitn(2, ':');
    let last = parts.next().unwrap_or("");
    let head = parts.next();
    let (host, https) = match last {
        "https" => (head.unwrap_or("").to_string(), true),
        "http" => (head.unwrap_or("").to_string(), false),
        _ => (rest.to_string(), false),
    };
    if host.is_empty() {
        return Err("host is empty".into());
    }
    Ok(EnvHost {
        env: env.to_string(),
        host,
        https,
    })
}

#[derive(Debug, Deserialize, Serialize)]
struct ServiceConfig {
    #[serde(default)]
    port: Option<i32>,
    #[serde(rename = "environmentConfigs", default)]
    environment_configs: Vec<EnvServiceConfig>,
}

#[derive(Debug, Deserialize, Serialize)]
struct EnvServiceConfig {
    #[serde(rename = "environmentName", default)]
    environment_name: Option<String>,
    #[serde(default)]
    host: Option<String>,
    #[serde(default)]
    https: Option<bool>,
}

pub async fn run(client: Client, cmd: ServiceCommand, format: Format) -> Result<()> {
    match cmd {
        ServiceCommand::Get(args) => get(client, args, format).await,
        ServiceCommand::Set(args) => set(client, args, format).await,
    }
}

async fn get(client: Client, args: GetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/service",
        args.namespace, args.name
    );
    let config: ServiceConfig = client.get(&path).await?;
    output::render(format, &config, |c| {
        println!("Port: {}", c.port.map_or("-".into(), |p| p.to_string()));
        let rows = c.environment_configs.iter().map(|env_config| {
            vec![
                output::unwrap_or_dash(env_config.environment_name.as_deref()),
                output::unwrap_or_dash(env_config.host.as_deref()),
                env_config.https.unwrap_or(false).to_string(),
            ]
        });
        output::print_table(&["ENV", "HOST", "HTTPS"], rows);
    })
}

async fn set(client: Client, args: SetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/service",
        args.namespace, args.name
    );
    let environment_configs: Vec<_> = args
        .env_hosts
        .iter()
        .map(|entry| {
            json!({
                "environmentName": entry.env,
                "host": entry.host,
                "https": entry.https,
            })
        })
        .collect();
    let body = json!({
        "namespace": args.namespace,
        "applicationName": args.name,
        "port": args.port,
        "environmentConfigs": environment_configs,
    });
    let _: bool = client.send_put(&path, &body).await?;
    output::render(format, &json!({"updated": true}), |_| {
        println!("Service config updated for {}/{}", args.namespace, args.name);
    })
}
