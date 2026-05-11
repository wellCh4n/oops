use anyhow::Result;
use clap::Subcommand;
use serde::{Deserialize, Serialize};

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum EnvCommand {
    /// List environments.
    Ls,
}

#[derive(Debug, Deserialize, Serialize)]
struct Environment {
    id: String,
    name: String,
    #[serde(rename = "workNamespace", default)]
    work_namespace: Option<String>,
    #[serde(rename = "kubernetesApiServer", default)]
    kubernetes_api_server: Option<KubernetesApiServer>,
    #[serde(rename = "imageRepository", default)]
    image_repository: Option<ImageRepository>,
}

#[derive(Debug, Deserialize, Serialize)]
struct KubernetesApiServer {
    #[serde(default)]
    url: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
struct ImageRepository {
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    username: Option<String>,
}

pub async fn run(client: Client, cmd: EnvCommand, format: Format) -> Result<()> {
    match cmd {
        EnvCommand::Ls => list(client, format).await,
    }
}

async fn list(client: Client, format: Format) -> Result<()> {
    let items: Vec<Environment> = client.get("/openapi/environments").await?;
    output::render(format, &items, |list| {
        let rows = list.iter().map(|environment| {
            vec![
                environment.name.clone(),
                environment
                    .kubernetes_api_server
                    .as_ref()
                    .and_then(|server| server.url.clone())
                    .filter(|s| !s.is_empty())
                    .unwrap_or_else(|| "-".into()),
                output::unwrap_or_dash(environment.work_namespace.as_deref()),
                environment
                    .image_repository
                    .as_ref()
                    .and_then(|repo| repo.url.clone())
                    .filter(|s| !s.is_empty())
                    .unwrap_or_else(|| "-".into()),
            ]
        });
        output::print_table(&["NAME", "K8S URL", "WORK NS", "IMAGE REPO"], rows);
    })
}
