use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

const CONFIG_FILE: &str = "config.toml";
const CONFIG_DIR: &str = ".oops";

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Config {
    pub endpoint: Option<String>,
    pub token: Option<String>,
}

impl Config {
    pub fn load() -> Result<Self> {
        let path = config_path()?;
        if !path.exists() {
            return Ok(Self::default());
        }
        let body = std::fs::read_to_string(&path)
            .with_context(|| format!("read {}", path.display()))?;
        let config: Config = toml::from_str(&body)
            .with_context(|| format!("parse {}", path.display()))?;
        Ok(config)
    }

    pub fn save(&self) -> Result<PathBuf> {
        let path = config_path()?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("create {}", parent.display()))?;
        }
        let body = toml::to_string_pretty(self).context("serialize config")?;
        std::fs::write(&path, body).with_context(|| format!("write {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&path)?.permissions();
            perms.set_mode(0o600);
            std::fs::set_permissions(&path, perms)?;
        }
        Ok(path)
    }
}

pub fn config_path() -> Result<PathBuf> {
    let home = dirs::home_dir().context("locate home directory")?;
    Ok(home.join(CONFIG_DIR).join(CONFIG_FILE))
}

#[derive(Debug, Clone)]
pub struct ResolvedConfig {
    pub endpoint: String,
    pub token: String,
}

pub fn resolve(
    config: &Config,
    cli_endpoint: Option<String>,
    cli_token: Option<String>,
) -> Result<ResolvedConfig> {
    let endpoint = cli_endpoint
        .or_else(|| std::env::var("OOPS_ENDPOINT").ok())
        .or_else(|| config.endpoint.clone())
        .context("no endpoint configured (run `oops auth login` or pass --endpoint)")?;
    let token = cli_token
        .or_else(|| std::env::var("OOPS_TOKEN").ok())
        .or_else(|| config.token.clone())
        .context("no access token configured (run `oops auth login` or pass --token)")?;
    Ok(ResolvedConfig {
        endpoint: endpoint.trim_end_matches('/').to_string(),
        token,
    })
}
