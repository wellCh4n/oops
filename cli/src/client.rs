use anyhow::{Context, Result, anyhow, bail};
use reqwest::{Method, Response, StatusCode};
use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

use crate::config::ResolvedConfig;

pub struct Client {
    http: reqwest::Client,
    endpoint: String,
    token: String,
}

#[derive(Debug, Deserialize)]
pub struct Envelope<T> {
    pub success: bool,
    pub message: Option<String>,
    pub data: Option<T>,
}

#[derive(Debug, Deserialize)]
pub struct Page<T> {
    pub total: i64,
    pub data: Vec<T>,
    #[allow(dead_code)]
    pub size: i32,
    #[serde(rename = "totalPages")]
    #[allow(dead_code)]
    pub total_pages: i32,
}

impl Client {
    pub fn new(config: ResolvedConfig) -> Result<Self> {
        let http = reqwest::Client::builder()
            .build()
            .context("build http client")?;
        Ok(Self {
            http,
            endpoint: config.endpoint,
            token: config.token,
        })
    }

    pub async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.send_envelope(Method::GET, path, None::<&Value>).await
    }

    pub async fn post<B: serde::Serialize, T: DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
    ) -> Result<T> {
        self.send_envelope(Method::POST, path, Some(body)).await
    }

    pub async fn send_put<B: serde::Serialize, T: DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
    ) -> Result<T> {
        self.send_envelope(Method::PUT, path, Some(body)).await
    }

    pub async fn put_no_body<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.send_envelope::<Value, T>(Method::PUT, path, None).await
    }

    async fn send_envelope<B: serde::Serialize, T: DeserializeOwned>(
        &self,
        method: Method,
        path: &str,
        body: Option<&B>,
    ) -> Result<T> {
        let url = format!("{}{}", self.endpoint, path);
        let mut req = self
            .http
            .request(method.clone(), &url)
            .bearer_auth(&self.token);
        if let Some(b) = body {
            req = req.json(b);
        }
        let response = req
            .send()
            .await
            .with_context(|| format!("{} {}", method, url))?;
        let status = response.status();
        if !status.is_success() {
            return Err(http_error(method, &url, response).await);
        }
        let envelope: Envelope<T> = response
            .json()
            .await
            .with_context(|| format!("parse response from {}", url))?;
        if !envelope.success {
            bail!(
                "{} {}: {}",
                method,
                url,
                envelope.message.unwrap_or_else(|| "request failed".into())
            );
        }
        envelope
            .data
            .ok_or_else(|| anyhow!("{} {}: empty data", method, url))
    }

    pub async fn raw_put_bytes(
        &self,
        url: &str,
        bytes: Vec<u8>,
        headers: &[(String, String)],
    ) -> Result<()> {
        let mut req = self.http.put(url).body(bytes);
        for (key, value) in headers {
            req = req.header(key, value);
        }
        let response = req.send().await.with_context(|| format!("PUT {}", url))?;
        if !response.status().is_success() {
            return Err(http_error(Method::PUT, url, response).await);
        }
        Ok(())
    }
}

async fn http_error(method: Method, url: &str, response: Response) -> anyhow::Error {
    let status = response.status();
    let body = response.text().await.unwrap_or_default();
    if status == StatusCode::UNAUTHORIZED {
        return anyhow!("401 Unauthorized — check token (run `oops auth login`)");
    }
    if status == StatusCode::METHOD_NOT_ALLOWED {
        return anyhow!("405 Method Not Allowed — DELETE is not exposed via /openapi");
    }
    let snippet = if body.len() > 500 {
        format!("{}...", &body[..500])
    } else {
        body
    };
    anyhow!("{} {} → {} {}", method, url, status, snippet)
}
