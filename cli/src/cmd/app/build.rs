use anyhow::Result;
use clap::{Args, Subcommand, ValueEnum};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum BuildCommand {
    /// Show current build config.
    Get(GetArgs),
    /// Set source repo + dockerfile + build image.
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
    /// Source type: git or zip.
    #[arg(long, default_value = "git")]
    source: SourceTypeArg,
    /// For GIT: repository URL. For ZIP: leave empty (uploads pass the URL
    /// at deploy time).
    #[arg(long, default_value = "")]
    repository: String,
    /// Dockerfile source: builtin (use buildImage as-is) or user.
    #[arg(long, default_value = "user")]
    dockerfile_type: DockerfileTypeArg,
    /// Path to Dockerfile inside repo (required if dockerfile_type=user).
    #[arg(long, default_value = "Dockerfile")]
    dockerfile_path: String,
    /// Inline Dockerfile content (overrides --dockerfile-path if set).
    #[arg(long)]
    dockerfile_content: Option<String>,
    /// Custom builder image (e.g. node:20). Optional.
    #[arg(long)]
    build_image: Option<String>,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum SourceTypeArg {
    Git,
    Zip,
}

impl SourceTypeArg {
    fn api(&self) -> &'static str {
        match self {
            Self::Git => "GIT",
            Self::Zip => "ZIP",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum DockerfileTypeArg {
    Builtin,
    User,
}

impl DockerfileTypeArg {
    fn api(&self) -> &'static str {
        match self {
            Self::Builtin => "BUILTIN",
            Self::User => "USER",
        }
    }
}

#[derive(Debug, Deserialize, Serialize)]
struct BuildConfig {
    #[serde(rename = "sourceType", default)]
    source_type: Option<String>,
    #[serde(default)]
    repository: Option<String>,
    #[serde(rename = "buildImage", default)]
    build_image: Option<String>,
    #[serde(rename = "dockerFileConfig", default)]
    dockerfile: Option<DockerfileConfig>,
}

#[derive(Debug, Deserialize, Serialize)]
struct DockerfileConfig {
    #[serde(rename = "type", default)]
    file_type: Option<String>,
    #[serde(default)]
    path: Option<String>,
    #[serde(default)]
    content: Option<String>,
}

pub async fn run(client: Client, cmd: BuildCommand, format: Format) -> Result<()> {
    match cmd {
        BuildCommand::Get(args) => get(client, args, format).await,
        BuildCommand::Set(args) => set(client, args, format).await,
    }
}

async fn get(client: Client, args: GetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/build/config",
        args.namespace, args.name
    );
    let config: BuildConfig = client.get(&path).await?;
    output::render(format, &config, |c| {
        println!("Source:        {}", output::unwrap_or_dash(c.source_type.as_deref()));
        println!("Repository:    {}", output::unwrap_or_dash(c.repository.as_deref()));
        println!("Build image:   {}", output::unwrap_or_dash(c.build_image.as_deref()));
        if let Some(df) = &c.dockerfile {
            println!("Dockerfile:    type={}, path={}",
                output::unwrap_or_dash(df.file_type.as_deref()),
                output::unwrap_or_dash(df.path.as_deref()));
        }
    })
}

async fn set(client: Client, args: SetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/build/config",
        args.namespace, args.name
    );
    let dockerfile = json!({
        "type": args.dockerfile_type.api(),
        "path": args.dockerfile_path,
        "content": args.dockerfile_content.unwrap_or_default(),
    });
    let body = json!({
        "namespace": args.namespace,
        "applicationName": args.name,
        "sourceType": args.source.api(),
        "repository": args.repository,
        "dockerFileConfig": dockerfile,
        "buildImage": args.build_image.unwrap_or_default(),
    });
    let _: bool = client.send_put(&path, &body).await?;
    output::render(format, &json!({"updated": true}), |_| {
        println!("Build config updated for {}/{}", args.namespace, args.name);
    })
}
