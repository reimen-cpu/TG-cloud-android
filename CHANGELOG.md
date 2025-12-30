# Telegram Cloud Android - Release Notes

## v1.2.0 (2024-12-29)

### 🎉 Stable Release

First stable public release of Telegram Cloud for Android.

### ✨ Features

- **File Upload & Download**: Upload any file type to your private Telegram channel
- **Chunked File Support**: Large files (>20MB) are automatically split into chunks for reliable transfer
- **Gallery Sync**: Automatic backup of photos and videos from your device
- **Cross-Platform .link Files**: Share files securely using encrypted `.link` files (compatible with desktop version)
- **Progress Tracking**: Real-time progress bars for uploads, downloads, and gallery sync
- **File Management**: View, download, delete, and share your cloud files
- **Batch Operations**: Select and download/delete multiple files at once
- **Dark Theme**: Modern dark UI with Material 3 design
- **Resume Support**: Resume interrupted uploads and downloads

### 🐛 Bug Fixes

- Fixed chunked file detection for `.link` downloads (files >20MB now correctly identified)
- Fixed progress bar for `.link` downloads (no longer jumps from 0% to 100%)
- Fixed filename extension handling when moving files to Downloads
- Fixed multiple file downloads rate limiting (now uses sequential queue)
- Fixed password race condition in `.link` file downloads

### 🔐 Security

- All `.link` files are encrypted with user-defined passwords
- Keystore signing for release builds
- No external analytics or tracking

---

## 🚀 Roadmap - Future Releases

### v1.3.0 - Usability Improvements
- [ ] Search functionality for files
- [ ] Sort files by date, name, size
- [ ] File type filters (images, videos, documents)
- [ ] Improved thumbnail generation for videos
- [ ] Pull-to-refresh improvements

### v1.4.0 - Sync Enhancements
- [ ] Selective folder sync for gallery
- [ ] Background sync scheduling
- [ ] WiFi-only sync option
- [ ] Storage usage statistics
- [ ] Duplicate file detection

### v1.5.0 - Sharing & Collaboration
- [ ] Generate QR codes for `.link` files
- [ ] Share multiple files in single `.link`
- [ ] Link expiration settings
- [ ] Download count tracking

### v1.6.0 - Performance
- [ ] Parallel chunk downloads with smart rate limiting
- [ ] Compression options for uploads
- [ ] Cache management settings
- [ ] Memory optimization for large galleries

### v1.7.0 - Advanced Features
- [ ] Multiple Telegram channel support
- [ ] File encryption at rest
- [ ] Custom file tagging/categories
- [ ] Export/import app settings

### v2.0.0 - Major Release
- [ ] Tablet UI optimization
- [ ] Desktop companion app integration
- [ ] End-to-end encrypted sharing
- [ ] Offline file access (cached)

---

## 📝 Notes

- Minimum Android version: 8.0 (API 28)
- Target Android version: 14 (API 34)
- Requires a Telegram bot token and private channel for operation
