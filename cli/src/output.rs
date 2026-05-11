use anyhow::Result;
use comfy_table::{ContentArrangement, Table, presets::UTF8_FULL};
use serde::Serialize;

#[derive(Debug, Clone, Copy)]
pub enum Format {
    Human,
    Json,
}

impl Format {
    pub fn from_flag(json: bool) -> Self {
        if json { Self::Json } else { Self::Human }
    }
}

pub fn print_json<T: Serialize>(value: &T) -> Result<()> {
    let body = serde_json::to_string_pretty(value)?;
    println!("{}", body);
    Ok(())
}

pub fn print_table<I, R>(headers: &[&str], rows: I)
where
    I: IntoIterator<Item = R>,
    R: IntoIterator<Item = String>,
{
    let mut table = Table::new();
    table
        .load_preset(UTF8_FULL)
        .set_content_arrangement(ContentArrangement::Dynamic)
        .set_header(headers.iter().copied());
    for row in rows {
        table.add_row(row);
    }
    println!("{}", table);
}

pub fn render<T: Serialize, F: FnOnce(&T)>(format: Format, value: &T, human: F) -> Result<()> {
    match format {
        Format::Json => print_json(value),
        Format::Human => {
            human(value);
            Ok(())
        }
    }
}

pub fn unwrap_or_dash(value: Option<&str>) -> String {
    value.map(str::to_string).unwrap_or_else(|| "-".into())
}
