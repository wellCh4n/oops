use anyhow::Result;
use clap::Args;
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::client::{Client, Page};
use crate::output::{self, Format};

#[derive(Debug, Args)]
pub struct LsArgs {
    #[arg(short = 'n', long)]
    pub namespace: String,
    #[arg(long)]
    pub keyword: Option<String>,
    #[arg(long, default_value_t = 1)]
    pub page: u32,
    #[arg(long, default_value_t = 20)]
    pub size: u32,
}

#[derive(Debug, Args)]
pub struct GetArgs {
    #[arg(short = 'n', long)]
    pub namespace: String,
    pub name: String,
}

#[derive(Debug, Args)]
pub struct CreateArgs {
    #[arg(short = 'n', long)]
    pub namespace: String,
    pub name: String,
    #[arg(long, default_value = "")]
    pub description: String,
}

#[derive(Debug, Deserialize, Serialize)]
struct Application {
    #[serde(default)]
    id: Option<String>,
    name: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    namespace: Option<String>,
    #[serde(rename = "ownerName", default)]
    owner_name: Option<String>,
    #[serde(rename = "createdTime", default)]
    created_time: Option<String>,
}

pub async fn ls(client: Client, args: LsArgs, format: Format) -> Result<()> {
    let mut path = format!(
        "/openapi/namespaces/{}/applications?page={}&size={}",
        args.namespace, args.page, args.size
    );
    if let Some(kw) = args.keyword {
        path.push_str(&format!("&keyword={}", super::urlencoding(&kw)));
    }
    let result: Page<Application> = client.get(&path).await?;
    output::render(format, &result.data, |apps| {
        let rows = apps.iter().map(|app| {
            vec![
                app.name.clone(),
                output::unwrap_or_dash(app.description.as_deref()),
                output::unwrap_or_dash(app.owner_name.as_deref()),
                output::unwrap_or_dash(app.created_time.as_deref()),
            ]
        });
        output::print_table(&["NAME", "DESCRIPTION", "OWNER", "CREATED"], rows);
        println!("Total: {}", result.total);
    })
}

pub async fn get(client: Client, args: GetArgs, format: Format) -> Result<()> {
    let path = format!(
        "/openapi/namespaces/{}/applications/{}",
        args.namespace, args.name
    );
    let app: Application = client.get(&path).await?;
    output::render(format, &app, |app| {
        println!("Name:        {}", app.name);
        println!("Namespace:   {}", output::unwrap_or_dash(app.namespace.as_deref()));
        println!("Description: {}", output::unwrap_or_dash(app.description.as_deref()));
        println!("Owner:       {}", output::unwrap_or_dash(app.owner_name.as_deref()));
        println!("Created:     {}", output::unwrap_or_dash(app.created_time.as_deref()));
    })
}

pub async fn create(client: Client, args: CreateArgs, format: Format) -> Result<()> {
    let path = format!("/openapi/namespaces/{}/applications", args.namespace);
    let body = json!({
        "name": args.name,
        "description": args.description,
        "namespace": args.namespace,
    });
    let id: String = client.post(&path, &body).await?;
    let payload = json!({ "id": id, "name": args.name });
    output::render(format, &payload, |_| {
        println!("Created application '{}' (id={})", args.name, id);
    })
}
