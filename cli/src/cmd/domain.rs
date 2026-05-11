use anyhow::Result;
use clap::Subcommand;
use serde::{Deserialize, Serialize};

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum DomainCommand {
    /// List managed domains.
    Ls,
}

#[derive(Debug, Deserialize, Serialize)]
struct Domain {
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    host: Option<String>,
    #[serde(default)]
    https: Option<bool>,
    #[serde(rename = "certMode", default)]
    cert_mode: Option<String>,
    #[serde(rename = "hasUploadedCert", default)]
    has_uploaded_cert: Option<bool>,
    #[serde(default)]
    description: Option<String>,
}

pub async fn run(client: Client, cmd: DomainCommand, format: Format) -> Result<()> {
    match cmd {
        DomainCommand::Ls => list(client, format).await,
    }
}

async fn list(client: Client, format: Format) -> Result<()> {
    let items: Vec<Domain> = client.get("/openapi/domains").await?;
    output::render(format, &items, |list| {
        let rows = list.iter().map(|d| {
            vec![
                output::unwrap_or_dash(d.host.as_deref()),
                d.https.unwrap_or(false).to_string(),
                output::unwrap_or_dash(d.cert_mode.as_deref()),
                d.has_uploaded_cert.unwrap_or(false).to_string(),
                output::unwrap_or_dash(d.description.as_deref()),
            ]
        });
        output::print_table(
            &["HOST", "HTTPS", "CERT MODE", "UPLOADED", "DESCRIPTION"],
            rows,
        );
    })
}
