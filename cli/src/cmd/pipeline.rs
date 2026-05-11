use anyhow::{Result, bail};
use clap::Subcommand;
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::time::{Duration, sleep};

use crate::client::{Client, Page};
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum PipelineCommand {
    /// List pipelines for an application.
    Ls {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        #[arg(long)]
        env: Option<String>,
        #[arg(long, default_value_t = 1)]
        page: u32,
        #[arg(long, default_value_t = 10)]
        size: u32,
    },
    /// Get a single pipeline.
    Get {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        id: String,
    },
    /// Stop a running pipeline (PUT /stop).
    Stop {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        id: String,
    },
    /// Manually deploy a pipeline that's stuck in BUILD_SUCCEEDED
    /// (deployMode=MANUAL).
    Deploy {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        id: String,
    },
    /// Poll the pipeline until it reaches a terminal state.
    Watch {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        id: String,
        /// Polling interval in seconds.
        #[arg(long, default_value_t = 3)]
        interval: u64,
    },
}

#[derive(Debug, Deserialize, Serialize)]
pub struct Pipeline {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub environment: Option<String>,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub artifact: Option<String>,
    #[serde(rename = "deployMode", default)]
    pub deploy_mode: Option<String>,
    #[serde(rename = "operatorName", default)]
    pub operator_name: Option<String>,
    #[serde(rename = "createdTime", default)]
    pub created_time: Option<String>,
}

pub async fn run(client: Client, cmd: PipelineCommand, format: Format) -> Result<()> {
    match cmd {
        PipelineCommand::Ls {
            namespace,
            name,
            env,
            page,
            size,
        } => list(client, namespace, name, env, page, size, format).await,
        PipelineCommand::Get {
            namespace,
            name,
            id,
        } => get(client, namespace, name, id, format).await,
        PipelineCommand::Stop {
            namespace,
            name,
            id,
        } => stop(client, namespace, name, id, format).await,
        PipelineCommand::Deploy {
            namespace,
            name,
            id,
        } => manual_deploy(client, namespace, name, id, format).await,
        PipelineCommand::Watch {
            namespace,
            name,
            id,
            interval,
        } => {
            watch_pipeline(&client, &namespace, &name, &id, format, interval).await?;
            Ok(())
        }
    }
}

async fn list(
    client: Client,
    namespace: String,
    name: String,
    env: Option<String>,
    page: u32,
    size: u32,
    format: Format,
) -> Result<()> {
    let mut path = format!(
        "/openapi/namespaces/{}/applications/{}/pipelines?page={}&size={}",
        namespace, name, page, size
    );
    if let Some(env_name) = env {
        path.push_str(&format!("&environment={}", urlencoding(&env_name)));
    }
    let result: Page<Pipeline> = client.get(&path).await?;
    output::render(format, &result.data, |list| {
        let rows = list.iter().map(|pipeline| {
            vec![
                output::unwrap_or_dash(pipeline.id.as_deref()),
                output::unwrap_or_dash(pipeline.status.as_deref()),
                output::unwrap_or_dash(pipeline.environment.as_deref()),
                output::unwrap_or_dash(pipeline.deploy_mode.as_deref()),
                output::unwrap_or_dash(pipeline.operator_name.as_deref()),
                output::unwrap_or_dash(pipeline.created_time.as_deref()),
            ]
        });
        output::print_table(
            &["ID", "STATUS", "ENV", "MODE", "OPERATOR", "CREATED"],
            rows,
        );
        println!("Total: {}", result.total);
    })
}

async fn get(
    client: Client,
    namespace: String,
    name: String,
    id: String,
    format: Format,
) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/pipelines/{}",
        namespace, name, id
    );
    let pipeline: Pipeline = client.get(&path).await?;
    output::render(format, &pipeline, |p| {
        println!("ID:       {}", output::unwrap_or_dash(p.id.as_deref()));
        println!("Status:   {}", output::unwrap_or_dash(p.status.as_deref()));
        println!("Env:      {}", output::unwrap_or_dash(p.environment.as_deref()));
        println!("Branch:   {}", output::unwrap_or_dash(p.branch.as_deref()));
        println!("Artifact: {}", output::unwrap_or_dash(p.artifact.as_deref()));
        println!("Mode:     {}", output::unwrap_or_dash(p.deploy_mode.as_deref()));
        println!("Operator: {}", output::unwrap_or_dash(p.operator_name.as_deref()));
        println!("Created:  {}", output::unwrap_or_dash(p.created_time.as_deref()));
    })
}

async fn stop(
    client: Client,
    namespace: String,
    name: String,
    id: String,
    format: Format,
) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/pipelines/{}/stop",
        namespace, name, id
    );
    let _: bool = client.put_no_body(&path).await?;
    output::render(format, &json!({"stopped": id.clone()}), |_| {
        println!("Stop requested for pipeline {}", id);
    })
}

async fn manual_deploy(
    client: Client,
    namespace: String,
    name: String,
    id: String,
    format: Format,
) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/pipelines/{}/deploy",
        namespace, name, id
    );
    let _: bool = client.put_no_body(&path).await?;
    output::render(format, &json!({"deployed": id.clone()}), |_| {
        println!("Manual deploy triggered for pipeline {}", id);
    })
}

pub async fn watch_pipeline(
    client: &Client,
    namespace: &str,
    name: &str,
    id: &str,
    format: Format,
    interval_seconds: u64,
) -> Result<Pipeline> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/pipelines/{}",
        namespace, name, id
    );
    let interval = Duration::from_secs(interval_seconds.max(1));
    let mut last_status = String::new();
    loop {
        let pipeline: Pipeline = client.get(&path).await?;
        let status = pipeline.status.clone().unwrap_or_default();
        if status != last_status {
            if matches!(format, Format::Json) {
                println!(
                    "{}",
                    serde_json::to_string(&json!({"id": id, "status": status}))?
                );
            } else {
                println!("[{}] {}", id, status);
            }
            last_status = status.clone();
        }
        if is_terminal(&status) {
            return ensure_terminal_ok(&status).map(|_| pipeline);
        }
        sleep(interval).await;
    }
}

pub fn is_terminal(status: &str) -> bool {
    matches!(status, "SUCCEEDED" | "ERROR" | "STOPPED" | "BUILD_SUCCEEDED")
}

fn ensure_terminal_ok(status: &str) -> Result<()> {
    match status {
        "SUCCEEDED" | "BUILD_SUCCEEDED" => Ok(()),
        "ERROR" => bail!("pipeline ended with ERROR"),
        "STOPPED" => bail!("pipeline was STOPPED"),
        other => bail!("unexpected terminal status: {}", other),
    }
}

fn urlencoding(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}
