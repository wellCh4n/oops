use anyhow::Result;
use clap::Subcommand;
use serde::Deserialize;

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum NsCommand {
    /// List namespaces.
    Ls,
}

#[derive(Debug, Deserialize, serde::Serialize)]
struct Namespace {
    id: String,
    name: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(rename = "createdTime", default)]
    created_time: Option<String>,
}

pub async fn run(client: Client, cmd: NsCommand, format: Format) -> Result<()> {
    match cmd {
        NsCommand::Ls => list(client, format).await,
    }
}

async fn list(client: Client, format: Format) -> Result<()> {
    let items: Vec<Namespace> = client.get("/openapi/namespaces").await?;
    output::render(format, &items, |list| {
        let rows = list.iter().map(|namespace| {
            vec![
                namespace.name.clone(),
                output::unwrap_or_dash(namespace.description.as_deref()),
                output::unwrap_or_dash(namespace.created_time.as_deref()),
            ]
        });
        output::print_table(&["NAME", "DESCRIPTION", "CREATED"], rows);
    })
}
