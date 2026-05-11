use anyhow::{Context, Result};
use clap::Subcommand;
use serde::Deserialize;
use std::io::{self, Read};

use crate::client::Client;
use crate::config::{self, Config, ResolvedConfig};
use crate::output::Format;

#[derive(Debug, Subcommand)]
pub enum AuthCommand {
    /// Save endpoint + access token to ~/.oops/config.toml.
    ///
    /// Token is read from --token, OOPS_TOKEN, or stdin (in that order).
    /// Username/password login is not supported — issue an access token in
    /// the UI (POST /api/users/me/access-token/reset) first.
    Set {
        /// OOPS endpoint, e.g. http://localhost:8080
        #[arg(long)]
        endpoint: String,
        /// Access token (sk-oops-...). Reads from OOPS_TOKEN env var or
        /// stdin if omitted.
        #[arg(long)]
        token: Option<String>,
    },
    /// Show current endpoint and verify the token works.
    Status,
}

#[derive(Debug, Deserialize)]
struct NamespaceProbe {
    #[allow(dead_code)]
    name: String,
}

pub async fn run(cmd: AuthCommand, _format: Format) -> Result<()> {
    match cmd {
        AuthCommand::Set { endpoint, token } => set(endpoint, token).await,
        AuthCommand::Status => status().await,
    }
}

async fn set(endpoint: String, token: Option<String>) -> Result<()> {
    let token = match token {
        Some(value) => value,
        None => match std::env::var("OOPS_TOKEN").ok() {
            Some(value) => value,
            None => read_token_from_stdin()?,
        },
    };
    let endpoint = endpoint.trim_end_matches('/').to_string();
    let token = token.trim().to_string();
    if token.is_empty() {
        anyhow::bail!("token is empty");
    }
    let resolved = ResolvedConfig {
        endpoint: endpoint.clone(),
        token: token.clone(),
    };
    let client = Client::new(resolved)?;
    let _: Vec<NamespaceProbe> = client
        .get("/openapi/namespaces")
        .await
        .context("verify token against /openapi/namespaces")?;
    let config = Config {
        endpoint: Some(endpoint),
        token: Some(token),
    };
    let path = config.save()?;
    println!("Token verified. Saved to {}", path.display());
    Ok(())
}

async fn status() -> Result<()> {
    let config = Config::load()?;
    let endpoint = config.endpoint.as_deref().unwrap_or("-");
    let has_token = config.token.is_some();
    println!("Endpoint: {}", endpoint);
    println!("Token: {}", if has_token { "set" } else { "<unset>" });
    if !has_token || endpoint == "-" {
        return Ok(());
    }
    let resolved = config::resolve(&config, None, None)?;
    let client = Client::new(resolved)?;
    match client.get::<Vec<NamespaceProbe>>("/openapi/namespaces").await {
        Ok(items) => println!("Token OK ({} namespace(s) visible)", items.len()),
        Err(error) => println!("Token check failed: {}", error),
    }
    Ok(())
}

fn read_token_from_stdin() -> Result<String> {
    let mut buffer = String::new();
    io::stdin()
        .read_to_string(&mut buffer)
        .context("read token from stdin")?;
    Ok(buffer)
}
