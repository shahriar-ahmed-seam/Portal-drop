use anyhow::{Context, Result, ensure};
use std::{
    collections::HashSet,
    fs,
    io::ErrorKind,
    path::{Path, PathBuf},
};

pub const DIRECTORY: &str = "Folders";
const MAX_PATH_BYTES: usize = 1024;
const MAX_DEPTH: usize = 32;

pub fn validate(path: &str, filename: &str) -> Result<()> {
    let Some((_, leaf)) = path.rsplit_once('/') else {
        anyhow::bail!("folder path must include a top-level directory");
    };
    ensure!(
        path.len() <= MAX_PATH_BYTES && path.split('/').count() <= MAX_DEPTH && leaf == filename,
        "invalid folder path"
    );
    for name in path.split('/') {
        validate_segment(name)?;
    }
    Ok(())
}

fn validate_segment(name: &str) -> Result<()> {
    let stem = name
        .split('.')
        .next()
        .unwrap_or_default()
        .to_ascii_uppercase();
    let reserved = ["CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"].contains(&stem.as_str())
        || stem
            .strip_prefix("COM")
            .or_else(|| stem.strip_prefix("LPT"))
            .is_some_and(|n| {
                matches!(
                    n,
                    "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
                )
            });
    ensure!(
        !name.is_empty()
            && name.len() <= crate::model::MAX_NAME
            && !name.ends_with(['.', ' '])
            && !reserved
            && !name.chars().any(|ch| ch.is_control()
                || matches!(ch, '/' | '\\' | '<' | '>' | ':' | '"' | '|' | '?' | '*')),
        "folder contains an unsupported name: {name:?}"
    );
    Ok(())
}

pub fn children(root: &Path) -> Result<Vec<(PathBuf, String)>> {
    let root = std::path::absolute(root)?;
    ensure!(
        fs::symlink_metadata(&root)?.file_type().is_dir(),
        "choose an ordinary folder"
    );
    let name = root
        .file_name()
        .and_then(|name| name.to_str())
        .context("folder needs a UTF-8 name")?;
    validate_segment(name)?;
    let mut stack = vec![(root.clone(), name.to_owned())];
    let mut files = Vec::new();
    while let Some((source, relative)) = stack.pop() {
        let mut names = HashSet::new();
        for entry in fs::read_dir(&source)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            if !kind.is_file() && !kind.is_dir() {
                continue;
            }
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| anyhow::anyhow!("folder names must be UTF-8"))?;
            ensure!(
                names.insert(name.to_lowercase()),
                "folder contains names that differ only by case"
            );
            let path = format!("{relative}/{name}");
            validate(&path, &name)?;
            if kind.is_dir() {
                stack.push((entry.path(), path));
            } else {
                files.push((entry.path(), path));
            }
        }
    }
    ensure!(!files.is_empty(), "folder has no files to send");
    files.sort_unstable_by(|a, b| a.1.cmp(&b.1));
    Ok(files)
}

pub fn prepare_parent(target: &Path, relative_path: &str) -> Result<()> {
    let root = target
        .ancestors()
        .nth(relative_path.split('/').count() + 1)
        .context("missing folder receive root")?;
    let directory = target.parent().context("missing folder receive path")?;
    crate::store::private_directory(root)?;
    let mut parent = root.to_path_buf();
    for component in directory.strip_prefix(root)?.components() {
        parent.push(component);
        match fs::symlink_metadata(&parent) {
            Ok(metadata) => ensure!(
                metadata.file_type().is_dir(),
                "receive folder crosses a link or a file"
            ),
            Err(error) if error.kind() == ErrorKind::NotFound => {
                crate::store::private_directory(&parent)?;
                ensure!(
                    fs::symlink_metadata(&parent)?.file_type().is_dir(),
                    "receive folder crosses a link or a file"
                );
            }
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}
