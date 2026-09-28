# Auto Backup System

A Python-based, menu-driven backup tool for Linux that automates folder backups, allows restoration, logs actions, and can schedule daily backups. Ideal for personal use, businesses, or anyone managing important files.

## Features

- Backup any folder on your Linux machine
- Optional file type filters (e.g., `.pdf, .mp4`)
- Compress backups to save storage space
- Restore backups easily
- View backup logs
- Cleanup old backups automatically
- Schedule daily backups using the Python `schedule` module
- Error handling for missing folders or permissions

## Installation

```bash
# Clone the repository
git clone https://github.com/Lemi897/auto-backup.git
cd auto-backup

# Make the script executable
chmod +x auto_backup.py

# Install dependencies
python3 -m pip install --user schedule

# Or use a virtual environment
python3 -m venv venv
source venv/bin/activate
pip install schedule
```

## Usage

Run the script:

```bash
./auto_backup.py
```

Follow the menu:

```
=== Auto Backup System ===
1. Backup a folder
2. Restore a backup (copy files back)
3. View backup logs
4. Cleanup old backups
5. Schedule daily backup
6. Exit
```

- For backups, enter the folder path and optionally specify file types
- Choose whether to compress the backup (y/n)
- Backups are stored in `~/automation-projects/backups`
- Logs are stored in `~/automation-projects/backups/logs`

## Highlights

- Menu-driven interface for easy use
- Compression option for saving space
- Scheduled daily backups: automation without intervention
- Auto-logs to track all actions
- Cleanup of old backups keeps storage organized
- Handles errors gracefully instead of crashing

## Requirements

- Python 3.x
- `schedule` module (`pip install schedule`)
