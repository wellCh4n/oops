use anyhow::{Context, Result};
use clap::Subcommand;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum DeployCommand {
    /// Deploy from a Git repository.
    ///
    /// The application's build config must already be set with
    /// sourceType=GIT (see `oops app build set`).
    Git {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        #[arg(long)]
        env: String,
        /// Branch to build (defaults to whatever the build config's
        /// per-env branch resolves to).
        #[arg(long)]
        branch: Option<String>,
        #[arg(long, default_value = "immediate")]
        mode: DeployModeArg,
        /// Watch the pipeline until it reaches a terminal state.
        #[arg(long, default_value_t = false)]
        wait: bool,
    },
    /// Deploy from a local ZIP archive.
    Zip {
        #[arg(short = 'n', long)]
        namespace: String,
        name: String,
        #[arg(long)]
        env: String,
        #[arg(long)]
        file: PathBuf,
        #[arg(long, default_value = "immediate")]
        mode: DeployModeArg,
        #[arg(long, default_value_t = false)]
        wait: bool,
    },
}

#[derive(Debug, Clone, Copy, clap::ValueEnum)]
pub enum DeployModeArg {
    Immediate,
    Manual,
}

impl DeployModeArg {
    fn api(&self) -> &'static str {
        match self {
            Self::Immediate => "IMMEDIATE",
            Self::Manual => "MANUAL",
        }
    }
}

#[derive(Debug, Deserialize, Serialize)]
struct UploadResult {
    #[serde(rename = "objectKey")]
    object_key: String,
    #[serde(rename = "objectUrl")]
    object_url: String,
    #[serde(rename = "uploadUrl")]
    upload_url: String,
    #[serde(default)]
    headers: std::collections::HashMap<String, String>,
}

pub async fn run(client: Client, cmd: DeployCommand, format: Format) -> Result<()> {
    match cmd {
        DeployCommand::Git {
            namespace,
            name,
            env,
            branch,
            mode,
            wait,
        } => {
            let strategy = json!({
                "type": "GIT",
                "branch": branch.unwrap_or_default(),
            });
            deploy(client, &namespace, &name, &env, mode, strategy, wait, format).await
        }
        DeployCommand::Zip {
            namespace,
            name,
            env,
            file,
            mode,
            wait,
        } => {
            let object_url = upload_zip(&client, &namespace, &name, &file).await?;
            let strategy = json!({
                "type": "ZIP",
                "repository": object_url,
            });
            deploy(client, &namespace, &name, &env, mode, strategy, wait, format).await
        }
    }
}

async fn upload_zip(
    client: &Client,
    namespace: &str,
    name: &str,
    file: &Path,
) -> Result<String> {
    let bytes = tokio::fs::read(file)
        .await
        .with_context(|| format!("read {}", file.display()))?;
    let file_name = file
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("source.zip")
        .to_string();
    let content_type = mime_guess::from_path(file)
        .first()
        .map(|mime| mime.essence_str().to_string())
        .unwrap_or_else(|| "application/zip".into());
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/deployments/source-upload",
        namespace, name
    );
    let body = json!({
        "fileName": file_name,
        "fileSize": bytes.len() as u64,
        "contentType": content_type,
    });
    let upload: UploadResult = client.post(&path, &body).await?;
    println!(
        "Uploading {} ({} bytes) → object {}",
        file_name,
        bytes.len(),
        upload.object_key
    );
    let headers: Vec<(String, String)> = upload
        .headers
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    client
        .raw_put_bytes(&upload.upload_url, bytes, &headers)
        .await
        .context("upload zip to object storage")?;
    Ok(upload.object_url)
}

async fn deploy(
    client: Client,
    namespace: &str,
    name: &str,
    env: &str,
    mode: DeployModeArg,
    strategy: Value,
    wait: bool,
    format: Format,
) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/deployments",
        namespace, name
    );
    let body = json!({
        "environment": env,
        "deployMode": mode.api(),
        "strategy": strategy,
    });
    let pipeline_id: String = client.post(&path, &body).await?;
    let payload = json!({ "pipelineId": pipeline_id });
    output::render(format, &payload, |_| {
        println!("Triggered pipeline {}", pipeline_id);
    })?;
    if wait {
        crate::cmd::pipeline::watch_pipeline(&client, namespace, name, &pipeline_id, format, 3)
            .await?;
    }
    Ok(())
}
