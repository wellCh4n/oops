use anyhow::Result;
use clap::{Args, Subcommand};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::client::Client;
use crate::output::{self, Format};

#[derive(Debug, Subcommand)]
pub enum ConfigMapCommand {
    /// List ConfigMap entries for a given env.
    Ls(EnvScopedArgs),
    /// Replace ConfigMap entries for a given env. Pass --kv K=V repeatedly.
    Set(SetArgs),
}

#[derive(Debug, Args)]
pub struct EnvScopedArgs {
    #[arg(short = 'n', long)]
    namespace: String,
    name: String,
    #[arg(long)]
    env: String,
}

#[derive(Debug, Args)]
pub struct SetArgs {
    #[arg(short = 'n', long)]
    namespace: String,
    name: String,
    #[arg(long)]
    env: String,
    /// Key=Value pairs. Pass --kv multiple times. Empty list clears the
    /// ConfigMap.
    #[arg(long = "kv", value_parser = parse_kv)]
    kvs: Vec<KeyValue>,
}

#[derive(Debug, Clone)]
pub struct KeyValue {
    key: String,
    value: String,
}

fn parse_kv(input: &str) -> Result<KeyValue, String> {
    let (key, value) = input
        .split_once('=')
        .ok_or_else(|| "expected KEY=VALUE".to_string())?;
    if key.is_empty() {
        return Err("key is empty".into());
    }
    Ok(KeyValue {
        key: key.to_string(),
        value: value.to_string(),
    })
}

#[derive(Debug, Deserialize, Serialize)]
struct ConfigMapItem {
    #[serde(default)]
    key: Option<String>,
    #[serde(default)]
    value: Option<String>,
}

pub async fn run(client: Client, cmd: ConfigMapCommand, format: Format) -> Result<()> {
    match cmd {
        ConfigMapCommand::Ls(args) => list(client, args, format).await,
        ConfigMapCommand::Set(args) => set(client, args, format).await,
    }
}

async fn list(client: Client, args: EnvScopedArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/configmaps?environment={}",
        args.namespace,
        args.name,
        super::urlencoding(&args.env)
    );
    let items: Vec<ConfigMapItem> = client.get(&path).await?;
    output::render(format, &items, |list| {
        let rows = list.iter().map(|item| {
            vec![
                output::unwrap_or_dash(item.key.as_deref()),
                output::unwrap_or_dash(item.value.as_deref()),
            ]
        });
        output::print_table(&["KEY", "VALUE"], rows);
    })
}

async fn set(client: Client, args: SetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}/configmaps?environment={}",
        args.namespace,
        args.name,
        super::urlencoding(&args.env)
    );
    let body: Vec<_> = args
        .kvs
        .iter()
        .map(|kv| json!({"key": kv.key, "value": kv.value}))
        .collect();
    let _: bool = client.send_put(&path, &body).await?;
    output::render(
        format,
        &json!({"updated": true, "count": args.kvs.len(), "env": args.env}),
        |_| {
            println!(
                "ConfigMap updated for {}/{} (env={}, {} entries)",
                args.namespace, args.name, args.env, args.kvs.len()
            );
        },
    )
}
