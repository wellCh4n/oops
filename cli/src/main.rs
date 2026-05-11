mod client;
mod cmd;
mod config;
mod output;

use anyhow::Result;
use clap::{Parser, Subcommand};

use crate::client::Client;
use crate::config::{Config, resolve};
use crate::output::Format;

#[derive(Debug, Parser)]
#[command(name = "oops", about = "OOPS command-line client", version)]
struct Cli {
    /// OOPS endpoint URL (overrides config and OOPS_ENDPOINT).
    #[arg(long, global = true)]
    endpoint: Option<String>,
    /// Access token (overrides config and OOPS_TOKEN).
    #[arg(long, global = true)]
    token: Option<String>,
    /// Emit JSON instead of human-readable tables.
    #[arg(long, global = true, default_value_t = false)]
    json: bool,
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Manage local CLI auth state.
    Auth {
        #[command(subcommand)]
        cmd: cmd::auth::AuthCommand,
    },
    /// Namespaces.
    Ns {
        #[command(subcommand)]
        cmd: cmd::ns::NsCommand,
    },
    /// Environments.
    Env {
        #[command(subcommand)]
        cmd: cmd::env::EnvCommand,
    },
    /// Managed domains.
    Domain {
        #[command(subcommand)]
        cmd: cmd::domain::DomainCommand,
    },
    /// Applications.
    App {
        #[command(subcommand)]
        cmd: cmd::app::AppCommand,
    },
    /// Deploy an application.
    Deploy {
        #[command(subcommand)]
        cmd: cmd::deploy::DeployCommand,
    },
    /// Pipelines.
    Pipeline {
        #[command(subcommand)]
        cmd: cmd::pipeline::PipelineCommand,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let format = Format::from_flag(cli.json);

    match cli.command {
        Command::Auth { cmd } => cmd::auth::run(cmd, format).await,
        other => {
            let config = Config::load()?;
            let resolved = resolve(&config, cli.endpoint, cli.token)?;
            let client = Client::new(resolved)?;
            dispatch(client, other, format).await
        }
    }
}

async fn dispatch(client: Client, command: Command, format: Format) -> Result<()> {
    match command {
        Command::Auth { .. } => unreachable!(),
        Command::Ns { cmd } => cmd::ns::run(client, cmd, format).await,
        Command::Env { cmd } => cmd::env::run(client, cmd, format).await,
        Command::Domain { cmd } => cmd::domain::run(client, cmd, format).await,
        Command::App { cmd } => cmd::app::run(client, cmd, format).await,
        Command::Deploy { cmd } => cmd::deploy::run(client, cmd, format).await,
        Command::Pipeline { cmd } => cmd::pipeline::run(client, cmd, format).await,
    }
}
