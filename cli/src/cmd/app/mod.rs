use anyhow::Result;
use clap::Subcommand;

use crate::client::Client;
use crate::output::Format;

mod build;
mod configmap;
mod env_bind;
mod profile;
mod runtime;
mod service;

#[derive(Debug, Subcommand)]
pub enum AppCommand {
    /// List applications in a namespace.
    Ls(profile::LsArgs),
    /// Get one application by name.
    Get(profile::GetArgs),
    /// Create an application (profile only — configure build/service/env
    /// before deploying).
    Create(profile::CreateArgs),

    /// Show or update build config (source repo, dockerfile, build image).
    Build {
        #[command(subcommand)]
        cmd: build::BuildCommand,
    },
    /// Show or update service config (port + per-env hostnames).
    Service {
        #[command(subcommand)]
        cmd: service::ServiceCommand,
    },
    /// Show or update runtime spec (CPU/memory/replicas/health check).
    Runtime {
        #[command(subcommand)]
        cmd: runtime::RuntimeCommand,
    },
    /// Bind the application to environments.
    Env {
        #[command(subcommand)]
        cmd: env_bind::EnvBindCommand,
    },
    /// Show or update environment-variable ConfigMap entries.
    Config {
        #[command(subcommand)]
        cmd: configmap::ConfigMapCommand,
    },
}

pub async fn run(client: Client, cmd: AppCommand, format: Format) -> Result<()> {
    match cmd {
        AppCommand::Ls(args) => profile::ls(client, args, format).await,
        AppCommand::Get(args) => profile::get(client, args, format).await,
        AppCommand::Create(args) => profile::create(client, args, format).await,
        AppCommand::Build { cmd } => build::run(client, cmd, format).await,
        AppCommand::Service { cmd } => service::run(client, cmd, format).await,
        AppCommand::Runtime { cmd } => runtime::run(client, cmd, format).await,
        AppCommand::Env { cmd } => env_bind::run(client, cmd, format).await,
        AppCommand::Config { cmd } => configmap::run(client, cmd, format).await,
    }
}

pub fn urlencoding(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}
