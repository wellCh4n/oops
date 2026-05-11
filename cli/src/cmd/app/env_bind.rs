use anyhow::Result;
use clap::{Args, Subcommand};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum EnvBindCommand {
    /// List environment bindings.
    Ls(GetArgs),
    /// Set the full list of bound environments. Replaces existing bindings.
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
    /// Environment names to bind. Pass --env multiple times.
    #[arg(long = "env")]
    envs: Vec<String>,
}

#[derive(Debug, Deserialize, Serialize)]
struct Binding {
    #[serde(rename = "environmentName", default)]
    environment_name: Option<String>,
}

pub async fn run(client: Client, cmd: EnvBindCommand, format: Format) -> Result<()> {
    match cmd {
        EnvBindCommand::Ls(args) => list(client, args, format).await,
        EnvBindCommand::Set(args) => set(client, args, format).await,
    }
}

async fn list(client: Client, args: GetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/environments",
        args.namespace, args.name
    );
    let bindings: Vec<Binding> = client.get(&path).await?;
    output::render(format, &bindings, |list| {
        let rows = list
            .iter()
            .map(|b| vec![output::unwrap_or_dash(b.environment_name.as_deref())]);
        output::print_table(&["ENV"], rows);
    })
}

async fn set(client: Client, args: SetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/environments",
        args.namespace, args.name
    );
    let body: Vec<_> = args
        .envs
        .iter()
        .map(|env| {
            json!({
                "namespace": args.namespace,
                "applicationName": args.name,
                "environmentName": env,
            })
        })
        .collect();
    let _: bool = client.send_put(&path, &body).await?;
    output::render(format, &json!({"updated": true, "count": args.envs.len()}), |_| {
        println!("Bound {}/{} to {} environment(s)",
            args.namespace, args.name, args.envs.len());
    })
}
