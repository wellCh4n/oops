use anyhow::Result;
use clap::{Args, Subcommand};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum RuntimeCommand {
    /// Show the runtime spec.
    Get(GetArgs),
    /// Set per-environment resource limits + global health check.
    ///
    /// Pass --env repeatedly:
    /// `--env dev=cpu:200m/1,mem:256Mi/512Mi,replicas:1`
    /// All three pairs are optional; missing values fall back to the
    /// server's defaults.
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
    #[arg(long = "env", value_parser = parse_env_runtime)]
    envs: Vec<EnvRuntime>,
    /// Enable health check.
    #[arg(long, default_value_t = false)]
    health: bool,
    #[arg(long, default_value = "/")]
    health_path: String,
    #[arg(long, default_value_t = 10)]
    health_initial_delay: i32,
    #[arg(long, default_value_t = 10)]
    health_period: i32,
    #[arg(long, default_value_t = 1)]
    health_timeout: i32,
    #[arg(long, default_value_t = 3)]
    health_failure_threshold: i32,
}

#[derive(Debug, Clone, Default)]
pub struct EnvRuntime {
    env: String,
    cpu_request: Option<String>,
    cpu_limit: Option<String>,
    memory_request: Option<String>,
    memory_limit: Option<String>,
    replicas: Option<i32>,
}

fn parse_env_runtime(input: &str) -> Result<EnvRuntime, String> {
    let (env, rest) = input
        .split_once('=')
        .ok_or_else(|| "expected env=key:val,key:val".to_string())?;
    let mut runtime = EnvRuntime {
        env: env.to_string(),
        ..Default::default()
    };
    for pair in rest.split(',') {
        let (key, value) = pair
            .split_once(':')
            .ok_or_else(|| format!("expected key:value in '{}'", pair))?;
        match key {
            "cpu" => {
                let (req, lim) = split_req_limit(value);
                runtime.cpu_request = req;
                runtime.cpu_limit = lim;
            }
            "mem" | "memory" => {
                let (req, lim) = split_req_limit(value);
                runtime.memory_request = req;
                runtime.memory_limit = lim;
            }
            "replicas" => {
                runtime.replicas = Some(
                    value
                        .parse()
                        .map_err(|_| format!("replicas must be int, got {}", value))?,
                );
            }
            other => return Err(format!("unknown key '{}'", other)),
        }
    }
    Ok(runtime)
}

fn split_req_limit(value: &str) -> (Option<String>, Option<String>) {
    if let Some((req, lim)) = value.split_once('/') {
        (Some(req.to_string()), Some(lim.to_string()))
    } else {
        (Some(value.to_string()), Some(value.to_string()))
    }
}

#[derive(Debug, Deserialize, Serialize)]
struct RuntimeSpec {
    #[serde(rename = "environmentConfigs", default)]
    environment_configs: Vec<EnvRuntimeView>,
    #[serde(rename = "healthCheck", default)]
    health_check: Option<HealthCheckView>,
}

#[derive(Debug, Deserialize, Serialize)]
struct EnvRuntimeView {
    #[serde(rename = "environmentName", default)]
    environment_name: Option<String>,
    #[serde(rename = "cpuRequest", default)]
    cpu_request: Option<String>,
    #[serde(rename = "cpuLimit", default)]
    cpu_limit: Option<String>,
    #[serde(rename = "memoryRequest", default)]
    memory_request: Option<String>,
    #[serde(rename = "memoryLimit", default)]
    memory_limit: Option<String>,
    #[serde(default)]
    replicas: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize)]
struct HealthCheckView {
    #[serde(default)]
    enabled: Option<bool>,
    #[serde(default)]
    path: Option<String>,
}

pub async fn run(client: Client, cmd: RuntimeCommand, format: Format) -> Result<()> {
    match cmd {
        RuntimeCommand::Get(args) => get(client, args, format).await,
        RuntimeCommand::Set(args) => set(client, args, format).await,
    }
}

async fn get(client: Client, args: GetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/runtime-spec",
        args.namespace, args.name
    );
    let spec: RuntimeSpec = client.get(&path).await?;
    output::render(format, &spec, |s| {
        let rows = s.environment_configs.iter().map(|env_config| {
            vec![
                output::unwrap_or_dash(env_config.environment_name.as_deref()),
                fmt_req_limit(env_config.cpu_request.as_deref(), env_config.cpu_limit.as_deref()),
                fmt_req_limit(env_config.memory_request.as_deref(), env_config.memory_limit.as_deref()),
                env_config.replicas.map_or("-".into(), |r| r.to_string()),
            ]
        });
        output::print_table(&["ENV", "CPU req/limit", "MEM req/limit", "REPLICAS"], rows);
        if let Some(hc) = &s.health_check {
            println!(
                "Health: enabled={}, path={}",
                hc.enabled.unwrap_or(false),
                output::unwrap_or_dash(hc.path.as_deref())
            );
        }
    })
}

fn fmt_req_limit(req: Option<&str>, lim: Option<&str>) -> String {
    match (req, lim) {
        (Some(r), Some(l)) => format!("{}/{}", r, l),
        (Some(r), None) => r.to_string(),
        (None, Some(l)) => l.to_string(),
        (None, None) => "-".into(),
    }
}

async fn set(client: Client, args: SetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/runtime-spec",
        args.namespace, args.name
    );
    let environment_configs: Vec<_> = args
        .envs
        .iter()
        .map(|entry| {
            json!({
                "environmentName": entry.env,
                "cpuRequest": entry.cpu_request,
                "cpuLimit": entry.cpu_limit,
                "memoryRequest": entry.memory_request,
                "memoryLimit": entry.memory_limit,
                "replicas": entry.replicas,
            })
        })
        .collect();
    let body = json!({
        "namespace": args.namespace,
        "applicationName": args.name,
        "environmentConfigs": environment_configs,
        "healthCheck": {
            "enabled": args.health,
            "path": args.health_path,
            "initialDelaySeconds": args.health_initial_delay,
            "periodSeconds": args.health_period,
            "timeoutSeconds": args.health_timeout,
            "failureThreshold": args.health_failure_threshold,
        },
    });
    let _: bool = client.send_put(&path, &body).await?;
    output::render(format, &json!({"updated": true}), |_| {
        println!("Runtime spec updated for {}/{}", args.namespace, args.name);
    })
}
