#include "mainwindow.h"
#include "config.h"
#include "envmanager.h"
#include "database.h"
#include "telegramhandler.h"
#include "telegramnotifier.h"
#include "chunkedupload.h"
#include "chunkeddownload.h"
#include "batchoperations.h"
#include "logger.h"
#include "backupmanager.h"
#include <wx/hyperlink.h>
#include <wx/stdpaths.h>
#include <wx/filename.h>
#include "integrity_validation.h"
#include "distributed_validation.h"
#include "universallinkgenerator.h"
#include "universallinkdownloader.h"
#include "helpdialog.h"
#include "obfuscated_strings.h"
#include <wx/filedlg.h>
#include <wx/msgdlg.h>
#include <wx/dirdlg.h>
#include <wx/filename.h>
#include <wx/textdlg.h>
#include <wx/clipbrd.h>
#include <wx/timer.h>
#include <wx/hyperlink.h>
#include <fstream>
#include <filesystem>
#include <future>
#include <chrono>
#include <thread>
#include <random>
#include <iomanip>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <openssl/aes.h>
#include <openssl/rand.h>
#include <openssl/evp.h>
#include <openssl/sha.h>

namespace TelegramCloud {
void MainWindow::OnCreateBackup(wxCommandEvent&) {
    // Construir nombre de archivo
    auto now = std::time(nullptr);
    std::tm tmNow{};
#ifdef _WIN32
    localtime_s(&tmNow, &now);
#else
    tmNow = *std::localtime(&now);
#endif
    char buf[128];
    std::strftime(buf, sizeof(buf), "TG cloud backup %d-%m-%Y %H-%M.zip", &tmNow);

    std::string outPath = std::string("backups/") + buf;

    // Password opcional
    wxPasswordEntryDialog pwdDlg(this, "Encrypt backup with a password (optional). Leave empty for no encryption.", "Backup Encryption");
    pwdDlg.SetValue("");
    pwdDlg.SetFocus();
    pwdDlg.ShowModal();
    std::string pwd = std::string(pwdDlg.GetValue().mb_str());

    // Mostrar estado en UI
    wxTheApp->CallAfter([this]() {
        m_uploadProgress->Show();
        m_uploadProgress->Pulse();
        m_uploadStatusLabel->SetLabel("Creating backup...");
        m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
    });

    // Ejecutar en thread secundario para no bloquear UI
    std::thread([this, outPath, pwd, label=wxString(buf)]() {
        bool ok = BackupManager::createZipBackup(outPath, pwd);
        if (!ok) {
            wxTheApp->CallAfter([this]() {
                m_uploadProgress->Hide();
                wxMessageBox("Failed to create backup (.zip).", "Backup", wxOK | wxICON_ERROR);
            });
            return;
        }

        // Subir a Telegram
        wxString caption = wxString::Format("TG cloud backup %s", label);
        UploadResult res = m_telegramHandler->uploadDocument(outPath, std::string(caption.mb_str()));

        wxTheApp->CallAfter([this, res]() {
            m_uploadProgress->Hide();
            if (res.success) {
                wxMessageBox("Backup created and uploaded successfully.", "Backup", wxOK | wxICON_INFORMATION);
            } else {
                wxMessageBox("Backup created locally but failed to upload.", "Backup", wxOK | wxICON_WARNING);
            }
            m_uploadStatusLabel->SetLabel("Ready");
            m_uploadStatusLabel->SetForegroundColour(wxColour(100,100,100));
        });
    }).detach();
}

void MainWindow::OnRestoreBackup(wxCommandEvent&) {
    wxFileDialog openDialog(this, "Select Backup (.zip)", "", "",
                            "ZIP archives (*.zip)|*.zip", wxFD_OPEN | wxFD_FILE_MUST_EXIST);
    if (openDialog.ShowModal() != wxID_OK) return;

    std::string path = std::string(openDialog.GetPath().mb_str());
    // Intentar leer manifest de manera segura: pediremos password si falla sin ella
    std::string pwd;
    if (!BackupManager::restoreZipBackup(path, "")) {
        wxPasswordEntryDialog pwdDlg(this, "This backup may be encrypted. Enter password to continue.", "Backup Password");
        if (pwdDlg.ShowModal() != wxID_OK) return;
        pwd = std::string(pwdDlg.GetValue().mb_str());
        if (!BackupManager::restoreZipBackup(path, pwd)) {
            wxMessageBox("Failed to restore backup. Wrong password?", "Restore", wxOK | wxICON_ERROR);
            return;
        }
    }

    wxMessageBox("Backup restored. The application will restart now.", "Restore", wxOK | wxICON_INFORMATION);
    wxString exePath = wxStandardPaths::Get().GetExecutablePath();
    if (!exePath.IsEmpty()) {
        wxExecute(exePath, wxEXEC_ASYNC);
    }
    Close(true);
    std::exit(0);
}
bool MainWindow::ShowConfigurationWizard(bool prefillFromEnv) {
    wxDialog dialog(this, wxID_ANY, "First-time Setup", wxDefaultPosition, wxSize(720, 560));
    dialog.SetBackgroundColour(wxColour(32, 32, 32));
    wxBoxSizer* root = new wxBoxSizer(wxVERTICAL);

    // Title
    wxStaticText* title = new wxStaticText(&dialog, wxID_ANY, "Welcome to Telegram Cloud Desktop");
    wxFont tf = title->GetFont();
    tf.SetPointSize(16);
    tf.SetWeight(wxFONTWEIGHT_BOLD);
    title->SetFont(tf);
    title->SetForegroundColour(wxColour(150, 200, 255));
    root->Add(title, 0, wxALL | wxALIGN_CENTER_HORIZONTAL, 14);

    // Intro + ayuda
    wxBoxSizer* introSizer = new wxBoxSizer(wxVERTICAL);
    wxStaticText* intro = new wxStaticText(&dialog, wxID_ANY,
        "Configure the connection. Provide 1-5 bot tokens and a channel ID.\n"
        "You can change these later from the .env file.");
    intro->SetForegroundColour(wxColour(210, 210, 210));
    introSizer->Add(intro, 0, wxBOTTOM, 8);

    wxStaticText* help = new wxStaticText(&dialog, wxID_ANY,
        "How to get credentials:");
    help->SetForegroundColour(wxColour(140, 190, 255));
    introSizer->Add(help, 0, wxBOTTOM, 4);

    wxBoxSizer* links = new wxBoxSizer(wxHORIZONTAL);
    wxHyperlinkCtrl* linkBotFather = new wxHyperlinkCtrl(&dialog, wxID_ANY, "Open @BotFather",
        "https://t.me/BotFather");
    wxHyperlinkCtrl* linkUserInfo = new wxHyperlinkCtrl(&dialog, wxID_ANY, "Open @userinfobot",
        "https://t.me/userinfobot");
    linkBotFather->SetNormalColour(wxColour(120, 190, 255));
    linkUserInfo->SetNormalColour(wxColour(120, 190, 255));
    linkBotFather->SetVisitedColour(wxColour(160, 200, 255));
    linkUserInfo->SetVisitedColour(wxColour(160, 200, 255));
    links->Add(linkBotFather, 0, wxRIGHT, 12);
    links->Add(linkUserInfo, 0);
    introSizer->Add(links, 0, wxBOTTOM, 6);

    wxStaticText* steps = new wxStaticText(&dialog, wxID_ANY,
        "1) Use @BotFather -> /newbot -> copy the token (repeat for multiple).\n"
        "2) Create a private channel, add all bots as admins.\n"
        "3) Use @userinfobot and resend him a message from your channel to get Channel ID (starts with -100).");
    steps->SetForegroundColour(wxColour(195, 195, 195));
    introSizer->Add(steps, 0, wxBOTTOM, 8);

    // Paso 1 con enlace embebido
    wxBoxSizer* step1 = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* s1a = new wxStaticText(&dialog, wxID_ANY, "1) Use ");
    s1a->SetForegroundColour(wxColour(195, 195, 195));
    wxHyperlinkCtrl* s1link = new wxHyperlinkCtrl(&dialog, wxID_ANY, "@BotFather", "https://t.me/BotFather");
    s1link->SetNormalColour(wxColour(120, 190, 255));
    s1link->SetVisitedColour(wxColour(160, 200, 255));
    wxStaticText* s1b = new wxStaticText(&dialog, wxID_ANY, " -> /newbot -> copy the token (repeat for multiple).");
    s1b->SetForegroundColour(wxColour(195, 195, 195));
    step1->Add(s1a, 0, wxRIGHT, 2);
    step1->Add(s1link, 0, wxRIGHT, 2);
    step1->Add(s1b, 0);

    // Paso 2
    wxStaticText* step2 = new wxStaticText(&dialog, wxID_ANY, "2) Create a private channel, add all bots as admins.");
    step2->SetForegroundColour(wxColour(195, 195, 195));

    // Paso 3 con enlace embebido
    wxBoxSizer* step3 = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* s3a = new wxStaticText(&dialog, wxID_ANY, "3) Use ");
    s3a->SetForegroundColour(wxColour(195, 195, 195));
    wxHyperlinkCtrl* s3link = new wxHyperlinkCtrl(&dialog, wxID_ANY, "@userinfobot", "https://t.me/userinfobot");
    s3link->SetNormalColour(wxColour(120, 190, 255));
    s3link->SetVisitedColour(wxColour(160, 200, 255));
    wxStaticText* s3b = new wxStaticText(&dialog, wxID_ANY, " inside the channel to get Channel ID (starts with -100).");
    s3b->SetForegroundColour(wxColour(195, 195, 195));
    step3->Add(s3a, 0, wxRIGHT, 2);
    step3->Add(s3link, 0, wxRIGHT, 2);
    step3->Add(s3b, 0);

    introSizer->Add(step1, 0, wxBOTTOM, 4);
    introSizer->Add(step2, 0, wxBOTTOM, 4);
    introSizer->Add(step3, 0, wxBOTTOM, 8);

    root->Add(introSizer, 0, wxLEFT | wxRIGHT | wxBOTTOM, 10);

    // Grouped sections for better clarity
    wxStaticBox* tokensBox = new wxStaticBox(&dialog, wxID_ANY, "Bot Tokens");
    tokensBox->SetForegroundColour(wxColour(230, 230, 230));
    wxStaticBoxSizer* tokensSizer = new wxStaticBoxSizer(tokensBox, wxVERTICAL);

    wxFlexGridSizer* grid = new wxFlexGridSizer(0, 2, 10, 12);
    grid->AddGrowableCol(1, 1);

    auto label = [&](const wxString& text) {
        wxStaticText* l = new wxStaticText(&dialog, wxID_ANY, text);
        l->SetForegroundColour(wxColour(200, 200, 200));
        return l;
    };

    wxTextCtrl* tokenEdits[5] = {nullptr};
    for (int i = 0; i < 5; ++i) {
        grid->Add(label(wxString::Format("Bot Token %d", i + 1)), 0, wxALIGN_CENTER_VERTICAL);
        tokenEdits[i] = new wxTextCtrl(&dialog, wxID_ANY, "", wxDefaultPosition, wxDefaultSize, 0);
        tokenEdits[i]->SetHint("123456:ABC-DEF1234...");
        tokenEdits[i]->SetMinSize(wxSize(420, -1));
        tokenEdits[i]->SetToolTip("Paste the bot token provided by @BotFather");
        grid->Add(tokenEdits[i], 1, wxEXPAND);
    }
    tokensSizer->Add(grid, 0, wxALL | wxEXPAND, 10);

    wxStaticBox* channelBox = new wxStaticBox(&dialog, wxID_ANY, "Channel & Notifications");
    channelBox->SetForegroundColour(wxColour(230, 230, 230));
    wxStaticBoxSizer* channelSizer = new wxStaticBoxSizer(channelBox, wxVERTICAL);

    wxFlexGridSizer* channelGrid = new wxFlexGridSizer(0, 2, 10, 12);
    channelGrid->AddGrowableCol(1, 1);

    channelGrid->Add(label("Channel ID"), 0, wxALIGN_CENTER_VERTICAL);
    wxTextCtrl* channelIdEdit = new wxTextCtrl(&dialog, wxID_ANY, "", wxDefaultPosition, wxDefaultSize, 0);
    channelIdEdit->SetHint("-100XXXXXXXXXX");
    channelIdEdit->SetToolTip("Paste the Channel ID returned by @userinfobot (starts with -100)");
    channelIdEdit->SetMinSize(wxSize(420, -1));
    channelGrid->Add(channelIdEdit, 1, wxEXPAND);

    channelGrid->Add(label("Notification Chat ID (optional)"), 0, wxALIGN_CENTER_VERTICAL);
    wxTextCtrl* chatIdEdit = new wxTextCtrl(&dialog, wxID_ANY, "", wxDefaultPosition, wxDefaultSize, 0);
    chatIdEdit->SetHint("Optional chat or channel for notifications");
    chatIdEdit->SetMinSize(wxSize(420, -1));
    channelGrid->Add(chatIdEdit, 1, wxEXPAND);

    channelSizer->Add(channelGrid, 0, wxALL | wxEXPAND, 10);

    root->Add(tokensSizer, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, 12);
    root->Add(channelSizer, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, 12);

    // Buttons
    wxBoxSizer* buttons = new wxBoxSizer(wxHORIZONTAL);
    wxButton* cancel = new wxButton(&dialog, wxID_CANCEL, "Cancel");
    wxButton* importBtn = new wxButton(&dialog, wxID_ANY, "Import backup (.zip)...");
    importBtn->SetMinSize(wxSize(140, 36));
    importBtn->SetBackgroundColour(*wxWHITE);
    importBtn->SetForegroundColour(wxColour(30, 30, 30));
    wxButton* save = new wxButton(&dialog, wxID_OK, "Save & Restart");
    wxFont bf = save->GetFont();
    bf.SetWeight(wxFONTWEIGHT_BOLD);
    save->SetFont(bf);
    save->SetMinSize(wxSize(140, 36));
    cancel->SetMinSize(wxSize(110, 36));
    save->SetDefault();
    buttons->Add(cancel, 0, wxALL, 5);
    buttons->Add(importBtn, 0, wxALL, 5);
    buttons->AddStretchSpacer();
    buttons->Add(save, 0, wxALL, 5);
    root->Add(buttons, 0, wxALL | wxEXPAND, 12);

    dialog.SetSizerAndFit(root);
    dialog.Layout();
    dialog.CentreOnScreen();

    // Habilitar "Continue" solo cuando haya al menos 1 token y channel ID
    auto updateContinueState = [&]() {
        bool hasToken = false;
        for (int i = 0; i < 5; ++i) {
            if (tokenEdits[i] && !tokenEdits[i]->GetValue().IsEmpty()) {
                hasToken = true; break;
            }
        }
        bool hasChannel = !channelIdEdit->GetValue().IsEmpty();
        save->Enable(hasToken && hasChannel);
    };
    for (int i = 0; i < 5; ++i) {
        if (tokenEdits[i]) {
            tokenEdits[i]->Bind(wxEVT_TEXT, [&, i](wxCommandEvent&) { updateContinueState(); });
        }
    }
    channelIdEdit->Bind(wxEVT_TEXT, [&](wxCommandEvent&) { updateContinueState(); });
    chatIdEdit->Bind(wxEVT_TEXT, [&](wxCommandEvent&) { /* no-op */ });
    updateContinueState();

    // Import backup handler
    importBtn->Bind(wxEVT_BUTTON, [&](wxCommandEvent&) {
        wxFileDialog openDialog(&dialog, "Select Backup (.zip)", "", "",
                                "ZIP archives (*.zip)|*.zip", wxFD_OPEN | wxFD_FILE_MUST_EXIST);
        if (openDialog.ShowModal() != wxID_OK) return;
        std::string path = std::string(openDialog.GetPath().mb_str());
        if (!BackupManager::restoreZipBackup(path)) {
            wxPasswordEntryDialog pwdDlg(&dialog, "This backup may be encrypted. Enter password to continue.", "Backup Password");
            if (pwdDlg.ShowModal() != wxID_OK) return;
            std::string pwd = std::string(pwdDlg.GetValue().mb_str());
            if (!BackupManager::restoreZipBackup(path, pwd)) {
                wxMessageBox("Failed to restore backup. Wrong password?", "Restore", wxOK | wxICON_ERROR);
                return;
            }
        }
        wxMessageBox("Backup restored. The application will restart now.", "Restore", wxOK | wxICON_INFORMATION);
        wxString exePath = wxStandardPaths::Get().GetExecutablePath();
        if (!exePath.IsEmpty()) {
            wxExecute(exePath, wxEXEC_ASYNC);
        }
        Close(true);
        std::exit(0);
    });

    // Prefill from environment only if requested (editing existing config)
    if (prefillFromEnv) {
        for (int i = 0; i < 5; ++i) {
            wxString key = wxString::Format("BOT_TOKEN_%d", i + 1);
            std::string v = EnvManager::instance().get(std::string(key.mb_str()));
            if (!v.empty()) tokenEdits[i]->SetValue(wxString::FromUTF8(v));
        }
        // Compatibilidad: si BOT_TOKEN existe y BOT_TOKEN_1 está vacío, prellenar
        if (tokenEdits[0]->IsEmpty()) {
            std::string single = EnvManager::instance().get("BOT_TOKEN");
            if (!single.empty()) tokenEdits[0]->SetValue(wxString::FromUTF8(single));
        }
        channelIdEdit->SetValue(wxString::FromUTF8(EnvManager::instance().get("CHANNEL_ID")));
        chatIdEdit->SetValue(wxString::FromUTF8(EnvManager::instance().get("CHAT_ID")));
    }

    if (dialog.ShowModal() != wxID_OK) {
        return false;
    }

    // Collect data
    std::vector<std::string> tokens;
    tokens.reserve(5);
    for (int i = 0; i < 5; ++i) {
        wxString t = tokenEdits[i]->GetValue();
        if (!t.IsEmpty()) tokens.push_back(std::string(t.mb_str()));
    }
    std::string channelId = std::string(channelIdEdit->GetValue().mb_str());
    std::string chatId = std::string(chatIdEdit->GetValue().mb_str());

    if (tokens.empty() || channelId.empty()) {
        wxMessageBox("Please provide at least 1 Bot Token and a Channel ID", "Setup", wxOK | wxICON_WARNING);
        return false;
    }

    // Write .env in executable directory
    try {
        std::filesystem::path exeDir = std::filesystem::absolute(std::filesystem::path("."));
        std::filesystem::path envPath = exeDir / ".env";
        std::ofstream env(envPath.string(), std::ios::out | std::ios::trunc);
        env << "# Telegram Cloud Configuration\n";
        env << "# Generated by Setup Wizard\n\n";
        // Escribir BOT_TOKEN (compatibilidad) y BOT_TOKEN_i
        if (!tokens.empty()) {
            env << "BOT_TOKEN=" << tokens[0] << "\n";
        }
        for (size_t i = 0; i < tokens.size(); ++i) {
            env << "BOT_TOKEN_" << (i + 1) << "=" << tokens[i] << "\n";
        }
        env << "CHANNEL_ID=" << channelId << "\n";
        if (!chatId.empty()) env << "CHAT_ID=" << chatId << "\n";
        env.close();

        // Also set in current EnvManager for this session
        for (size_t i = 0; i < tokens.size(); ++i) {
            EnvManager::instance().set("BOT_TOKEN_" + std::to_string(i + 1), tokens[i]);
        }
        if (!tokens.empty()) {
            EnvManager::instance().set("BOT_TOKEN", tokens[0]);
        }
        EnvManager::instance().set("CHANNEL_ID", channelId);
        if (!chatId.empty()) EnvManager::instance().set("CHAT_ID", chatId);

        wxMessageBox("Configuration saved. The application will restart now to apply settings.", "Setup", wxOK | wxICON_INFORMATION);

        // Relaunch executable then exit
        wxString exePath = wxStandardPaths::Get().GetExecutablePath();
        if (!exePath.IsEmpty()) {
            long pid = wxExecute(exePath, wxEXEC_ASYNC);
            (void)pid;
        }
        // Cerrar actual
        Close(true);
        std::exit(0);
        return false;
    } catch (const std::exception& e) {
        wxMessageBox(wxString::Format("Failed to save configuration: %s", e.what()), "Error", wxOK | wxICON_ERROR);
        return false;
    }
}

wxBEGIN_EVENT_TABLE(MainWindow, wxFrame)
    EVT_BUTTON(ID_UPLOAD_FILE, MainWindow::OnUploadFile)
    EVT_BUTTON(ID_UPLOAD_MULTIPLE, MainWindow::OnUploadMultiple)
    EVT_BUTTON(ID_REFRESH, MainWindow::OnRefresh)
    EVT_BUTTON(ID_DOWNLOAD, MainWindow::OnDownload)
    EVT_BUTTON(ID_DELETE, MainWindow::OnDelete)
    EVT_BUTTON(ID_SHARE, MainWindow::OnShare)
    EVT_BUTTON(ID_DOWNLOAD_FROM_LINK, MainWindow::OnDownloadFromLink)
    EVT_BUTTON(ID_SEARCH, MainWindow::OnSearch)
    EVT_TEXT(ID_SEARCH_TEXT, MainWindow::OnSearchTextChanged)
    EVT_BUTTON(ID_CLEAR_SEARCH, MainWindow::OnClearSearch)
    EVT_BUTTON(ID_CONTACT_BUTTON, MainWindow::OnContactButton)
    EVT_BUTTON(ID_COMMUNITY_BUTTON, MainWindow::OnCommunityButton)
    EVT_BUTTON(ID_PAUSE_UPLOAD, MainWindow::OnPauseUpload)
    EVT_BUTTON(ID_RESUME_UPLOAD, MainWindow::OnResumeUpload)
    EVT_BUTTON(ID_STOP_UPLOAD, MainWindow::OnStopUpload)
    EVT_BUTTON(ID_CANCEL_UPLOAD, MainWindow::OnCancelUpload)
    EVT_MENU(ID_CREATE_BACKUP, MainWindow::OnCreateBackup)
    EVT_MENU(ID_RESTORE_BACKUP, MainWindow::OnRestoreBackup)
    EVT_BUTTON(ID_CREATE_BACKUP, MainWindow::OnCreateBackup)
    EVT_BUTTON(ID_RESTORE_BACKUP, MainWindow::OnRestoreBackup)
    EVT_BUTTON(ID_SHOW_INCOMPLETE, MainWindow::OnShowIncompleteUploads)
    EVT_MENU(ID_SORT_BY_NAME, MainWindow::OnSortByName)
    EVT_MENU(ID_SORT_BY_SIZE, MainWindow::OnSortBySize)
    EVT_MENU(ID_SORT_BY_DATE, MainWindow::OnSortByDate)
    EVT_MENU(ID_SORT_BY_TYPE, MainWindow::OnSortByType)
EVT_MENU(ID_SORT_ASCENDING, MainWindow::OnSortAscending)
EVT_MENU(ID_SORT_DESCENDING, MainWindow::OnSortDescending)
EVT_MENU(ID_CONFIG, MainWindow::OnConfig)
EVT_MENU(ID_DECRYPT_FILE, MainWindow::OnDecryptFile)
EVT_TIMER(ID_ANIMATION_TIMER, MainWindow::OnAnimationTimer)
EVT_TIMER(ID_CONTACT_VALIDATION_TIMER, MainWindow::OnContactValidationTimer)
EVT_LIST_ITEM_ACTIVATED(wxID_ANY, MainWindow::OnListItemActivated)
EVT_LIST_ITEM_SELECTED(wxID_ANY, MainWindow::OnListItemClick)
EVT_LIST_ITEM_DESELECTED(wxID_ANY, MainWindow::OnListItemClick)
    EVT_MENU(wxID_EXIT, MainWindow::OnQuit)
    EVT_MENU(wxID_ABOUT, MainWindow::OnAbout)
    EVT_MENU(ID_HELP_GUIDE, MainWindow::OnHelpGuide)
wxEND_EVENT_TABLE()

MainWindow::MainWindow(bool configValid)
    : wxFrame(nullptr, wxID_ANY, "Telegram Cloud Desktop", 
              wxDefaultPosition, wxSize(1200, 800)),
    m_currentSearch(""),
    m_currentSortBy("name"),
    m_sortAscending(true),
    m_configValid(configValid),
    m_dotAnimationCounter(0),
    m_showDotAnimation(false),
    m_animationTimer(nullptr),
    m_contactValidationTimer(nullptr),
    m_currentOperationType(OperationType::NONE)
{
    LOG_INFO("Initializing MainWindow...");
    
    // Crear timer para animación de puntos
    m_animationTimer = new wxTimer(this, ID_ANIMATION_TIMER);
    
    // Set application icon from embedded resource
    wxIcon icon("IDI_ICON1");
    if (icon.IsOk()) {
        SetIcon(icon);
    } else {
        // Fallback to programmatic icon
        SetAppIcon();
    }
    
    // Configurar color de fondo oscuro
    SetBackgroundColour(wxColour(45, 45, 45));
    
    if (m_configValid) {
        if (!InitializeComponents()) {
            LOG_ERROR("Failed to initialize application components");
            wxMessageBox("Failed to initialize application components\n\nCheck logs/telegram_cloud_*.txt for details",
                        "Error", wxOK | wxICON_ERROR);
            Close(true);
            return;
        }
    } else {
        LOG_INFO("Configuration invalid - showing setup wizard");
        if (ShowConfigurationWizard(false)) {
            // Reintentar inicialización
            m_configValid = true;
            if (!InitializeComponents()) {
                LOG_CRITICAL("Failed to initialize components after wizard");
                wxMessageBox("Initialization failed after setup. Please restart the app.",
                            "Initialization Error", wxOK | wxICON_ERROR);
                Close(true);
                return;
            }
        } else {
            LOG_INFO("User cancelled setup wizard - limited mode");
        }
    }
    
    LOG_INFO("MainWindow initialized successfully");
    
    CreateMenuBar();
    CreateControls();
    CreateStatusBar();
    
    // Validar enlace de contacto protegido
    validateContactLink();
    
    // Crear timer para validación periódica del enlace
    m_contactValidationTimer = new wxTimer(this, ID_CONTACT_VALIDATION_TIMER);
    m_contactValidationTimer->Start(30000); // Validar cada 30 segundos
    
    Centre();
    
    if (m_configValid) {
        // IMPORTANTE: Corregir archivos huérfanos ANTES de cargar archivos
        if (m_database) {
            // Esto detecta y corrige archivos completados sin entrada en 'files'
            m_database->getIncompleteUploads();
        }
        
        LoadFiles();
        UpdateStats();
        
        // Verificar cargas y descargas incompletas al inicio
        CheckIncompleteUploadsOnStartup();
        CheckIncompleteDownloadsOnStartup();
    } else {
        // Update status to show limited mode
        if (m_serverStatusLabel) {
            m_serverStatusLabel->SetLabel("Status: Limited Mode (Configure to enable full features)");
            m_serverStatusLabel->SetForegroundColour(wxColour(255, 165, 0)); // Orange
        }
    }
}

MainWindow::~MainWindow() {
    LOG_INFO("Shutting down MainWindow...");
    
    // Detener TelegramNotifier
    if (m_telegramNotifier) {
        LOG_INFO("Stopping TelegramNotifier...");
        m_telegramNotifier->stop();
    }
    
    // Marcar todas las cargas y descargas activas como pausadas antes de cerrar
    if (m_database) {
        LOG_INFO("Marking active uploads as paused...");
        m_database->markAllActiveUploadsAsPaused();
        LOG_INFO("Marking active downloads as paused...");
        m_database->markAllActiveDownloadsAsPaused();
    }
    
    // Limpiar timers
    if (m_animationTimer) {
        m_animationTimer->Stop();
        delete m_animationTimer;
    }
    if (m_contactValidationTimer) {
        m_contactValidationTimer->Stop();
        delete m_contactValidationTimer;
    }
    
    LOG_INFO("MainWindow shutdown complete");
}

bool MainWindow::InitializeComponents() {
    Config& config = Config::instance();
    
    if (!config.isValid()) {
        std::string error = "Configuration Error:\n\n" + config.validationError() +
                          "\n\nPlease create .env file with credentials.";
        LOG_ERROR("Configuration validation failed: " + config.validationError());
        wxMessageBox(error, "Configuration Error", wxOK | wxICON_ERROR);
        return false;
    }
    
    // Inicializar TelegramHandler
    LOG_INFO("Initializing TelegramHandler...");
    m_telegramHandler = std::make_unique<TelegramHandler>();
    
     // Inicializar Database
     LOG_INFO("Initializing Database...");
     m_database = std::make_unique<Database>();
     if (!m_database->initialize(config.databasePath())) {
         LOG_ERROR("Failed to initialize database");
         wxMessageBox("Failed to initialize database.\n\nCheck logs for details.",
                     "Database Error", wxOK | wxICON_ERROR);
         return false;
     }
     
     // Inicializar BatchOperations
     LOG_INFO("Initializing BatchOperations...");
     m_batchOperations = std::make_unique<BatchOperations>(m_database.get(), m_telegramHandler.get());
     
     // Inicializar TelegramNotifier
     LOG_INFO("Initializing TelegramNotifier...");
     m_telegramNotifier = std::make_unique<TelegramNotifier>(m_database.get(), m_telegramHandler.get());
     m_telegramNotifier->start();
     
     LOG_INFO("All components initialized successfully");
     return true;
}

void MainWindow::CreateMenuBar() {
    wxMenuBar* menuBar = new wxMenuBar;
    
    wxMenu* fileMenu = new wxMenu;
    fileMenu->Append(ID_UPLOAD_FILE, "&Upload File...\tCtrl-O");
    fileMenu->Append(ID_REFRESH, "&Refresh\tF5");
    fileMenu->Append(ID_DECRYPT_FILE, "&Decrypt File...\tCtrl-D");
    fileMenu->AppendSeparator();
    fileMenu->Append(ID_CREATE_BACKUP, "Create &Backup (.zip)\tCtrl-B");
    fileMenu->Append(ID_RESTORE_BACKUP, "&Restore Backup (.zip)...");
    fileMenu->AppendSeparator();
    fileMenu->Append(wxID_EXIT);
    
    wxMenu* viewMenu = new wxMenu;
    viewMenu->Append(ID_SORT_BY_NAME, "Sort by &Name");
    viewMenu->Append(ID_SORT_BY_SIZE, "Sort by &Size");
    viewMenu->Append(ID_SORT_BY_DATE, "Sort by &Date");
    viewMenu->Append(ID_SORT_BY_TYPE, "Sort by &Type");
    viewMenu->AppendSeparator();
    viewMenu->Append(ID_SORT_ASCENDING, "&Ascending");
    viewMenu->Append(ID_SORT_DESCENDING, "&Descending");
    
    wxMenu* configMenu = new wxMenu;
    configMenu->Append(ID_CONFIG, "&Configuration...");
    
    wxMenu* guideMenu = new wxMenu;
    guideMenu->Append(ID_HELP_GUIDE, "&Open User Guide\tF1");

    menuBar->Append(fileMenu, "&File");
    menuBar->Append(viewMenu, "&View");
    menuBar->Append(configMenu, "&Config");
    menuBar->Append(guideMenu, "&User Guide");
    
    SetMenuBar(menuBar);
}

void MainWindow::CreateControls() {
    m_mainPanel = new wxPanel(this);
    m_mainPanel->SetBackgroundColour(wxColour(45, 45, 45));
    wxBoxSizer* mainSizer = new wxBoxSizer(wxVERTICAL);
    
    // Top bar spacer (no user guide button here; it's in the menu bar)
    
    // === Upload Section ===
    wxStaticBoxSizer* uploadBox = new wxStaticBoxSizer(wxVERTICAL, m_mainPanel, "Upload Files");
    wxStaticBox* uploadBoxCtrl = uploadBox->GetStaticBox();
    uploadBoxCtrl->SetFont(wxFont(10, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    uploadBoxCtrl->SetForegroundColour(*wxWHITE);
    
    wxBoxSizer* uploadButtonsSizer = new wxBoxSizer(wxHORIZONTAL);
    m_uploadButton = new wxButton(m_mainPanel, ID_UPLOAD_FILE, "Select File", wxDefaultPosition, wxSize(130, 32));
    m_uploadButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_uploadButton->SetForegroundColour(*wxWHITE);
    
    m_uploadMultipleButton = new wxButton(m_mainPanel, ID_UPLOAD_MULTIPLE, "Multiple Files", wxDefaultPosition, wxSize(130, 32));
    m_uploadMultipleButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_uploadMultipleButton->SetForegroundColour(*wxWHITE);
    
    m_downloadFromLinkButton = new wxButton(m_mainPanel, ID_DOWNLOAD_FROM_LINK, "Download from Link", wxDefaultPosition, wxSize(130, 32));
    m_downloadFromLinkButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_downloadFromLinkButton->SetForegroundColour(*wxWHITE);
    wxButton* backupButton = new wxButton(m_mainPanel, ID_CREATE_BACKUP, "Backup", wxDefaultPosition, wxSize(110, 32));
    backupButton->SetBackgroundColour(wxColour(60, 60, 60));
    backupButton->SetForegroundColour(*wxWHITE);
    wxButton* restoreButton = new wxButton(m_mainPanel, ID_RESTORE_BACKUP, "Restore", wxDefaultPosition, wxSize(110, 32));
    restoreButton->SetBackgroundColour(wxColour(60, 60, 60));
    restoreButton->SetForegroundColour(*wxWHITE);
    
    uploadButtonsSizer->Add(m_uploadButton, 0, wxALL, 5);
    uploadButtonsSizer->Add(m_uploadMultipleButton, 0, wxALL, 5);
    uploadButtonsSizer->Add(m_downloadFromLinkButton, 0, wxALL, 5);
    // separador flexible para empujar Backup/Restore a la derecha
    uploadButtonsSizer->AddStretchSpacer(1);
    uploadButtonsSizer->Add(backupButton, 0, wxALL, 5);
    uploadButtonsSizer->Add(restoreButton, 0, wxALL, 5);
    
    uploadBox->Add(uploadButtonsSizer, 1, wxALL | wxEXPAND, 5);
    
    // Checkbox para encriptación
    m_encryptFilesCheckBox = new wxCheckBox(m_mainPanel, wxID_ANY, "Encrypt files before upload");
    m_encryptFilesCheckBox->SetForegroundColour(*wxWHITE);
    m_encryptFilesCheckBox->SetValue(false);
    uploadBox->Add(m_encryptFilesCheckBox, 0, wxALL, 5);
    
    // Search box
    wxBoxSizer* searchSizer = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* searchLabel = new wxStaticText(m_mainPanel, wxID_ANY, "Search:");
    searchLabel->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    searchLabel->SetForegroundColour(*wxWHITE);
    
    m_searchTextCtrl = new wxTextCtrl(m_mainPanel, ID_SEARCH_TEXT, "", wxDefaultPosition, wxSize(200, 25));
    m_searchTextCtrl->SetHint("Search files...");
    m_searchTextCtrl->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    m_searchTextCtrl->SetBackgroundColour(wxColour(70, 70, 70));
    m_searchTextCtrl->SetForegroundColour(*wxWHITE);
    
    m_searchButton = new wxButton(m_mainPanel, ID_SEARCH, "Search", wxDefaultPosition, wxSize(80, 25));
    m_searchButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_searchButton->SetForegroundColour(*wxWHITE);
    
    m_clearSearchButton = new wxButton(m_mainPanel, ID_CLEAR_SEARCH, "Clear", wxDefaultPosition, wxSize(80, 25));
    m_clearSearchButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_clearSearchButton->SetForegroundColour(*wxWHITE);
    
    searchSizer->Add(searchLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    searchSizer->Add(m_searchTextCtrl, 1, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    searchSizer->Add(m_searchButton, 0, wxALL, 5);
    searchSizer->Add(m_clearSearchButton, 0, wxALL, 5);
    
    uploadBox->Add(searchSizer, 0, wxEXPAND | wxALL, 5);
    
    mainSizer->Add(uploadBox, 0, wxEXPAND | wxALL, 10);
    
    // === Files Section ===
    wxStaticBoxSizer* filesBox = new wxStaticBoxSizer(wxVERTICAL, m_mainPanel, "My Files");
    wxStaticBox* filesBoxCtrl = filesBox->GetStaticBox();
    filesBoxCtrl->SetFont(wxFont(10, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    filesBoxCtrl->SetForegroundColour(*wxWHITE);
    
     m_filesListCtrl = new wxListCtrl(m_mainPanel, wxID_ANY, wxDefaultPosition,
                                      wxDefaultSize, wxLC_REPORT | wxBORDER_SIMPLE);
     m_filesListCtrl->SetBackgroundColour(wxColour(70, 70, 70));
     m_filesListCtrl->SetForegroundColour(*wxWHITE);
     m_filesListCtrl->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
     
     // Agregar columna de checkbox (visual mediante símbolo)
     m_filesListCtrl->InsertColumn(0, "[ ]", wxLIST_FORMAT_CENTER, 40);
     m_filesListCtrl->InsertColumn(1, "Name", wxLIST_FORMAT_LEFT, 330);
     m_filesListCtrl->InsertColumn(2, "Size", wxLIST_FORMAT_RIGHT, 100);
     m_filesListCtrl->InsertColumn(3, "Type", wxLIST_FORMAT_LEFT, 140);
     m_filesListCtrl->InsertColumn(4, "Upload Date", wxLIST_FORMAT_LEFT, 170);
    
     // Fila de botones
     wxBoxSizer* filesButtonsSizer = new wxBoxSizer(wxHORIZONTAL);
     m_refreshButton = new wxButton(m_mainPanel, ID_REFRESH, "Refresh", wxDefaultPosition, wxSize(100, 32));
     m_refreshButton->SetBackgroundColour(wxColour(60, 60, 60));
     m_refreshButton->SetForegroundColour(*wxWHITE);
     
     m_downloadButton = new wxButton(m_mainPanel, ID_DOWNLOAD, "Download", wxDefaultPosition, wxSize(100, 32));
     m_downloadButton->SetBackgroundColour(wxColour(60, 60, 60));
     m_downloadButton->SetForegroundColour(*wxWHITE);
     
     m_deleteButton = new wxButton(m_mainPanel, ID_DELETE, "Delete", wxDefaultPosition, wxSize(100, 32));
     m_deleteButton->SetBackgroundColour(wxColour(60, 60, 60));
     m_deleteButton->SetForegroundColour(wxColour(255, 100, 100));
     
     m_shareButton = new wxButton(m_mainPanel, ID_SHARE, "Share", wxDefaultPosition, wxSize(100, 32));
     m_shareButton->SetBackgroundColour(wxColour(60, 60, 60));
     m_shareButton->SetForegroundColour(*wxWHITE);
     
     filesButtonsSizer->Add(m_refreshButton, 0, wxALL, 5);
     filesButtonsSizer->Add(m_downloadButton, 0, wxALL, 5);
     filesButtonsSizer->Add(m_deleteButton, 0, wxALL, 5);
     filesButtonsSizer->Add(m_shareButton, 0, wxALL, 5);
    
    // Fila de progreso (debajo de los botones)
    wxBoxSizer* progressSizer = new wxBoxSizer(wxHORIZONTAL);
    
    m_uploadProgress = new wxGauge(m_mainPanel, wxID_ANY, 100, wxPoint(-1, -1), wxSize(-1, 20));
    m_uploadProgress->Hide();
    
    m_uploadStatusLabel = new wxStaticText(m_mainPanel, wxID_ANY, "Ready");
    m_uploadStatusLabel->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
    
    progressSizer->Add(m_uploadProgress, 1, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 5);
    progressSizer->Add(m_uploadStatusLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    
    // Controles de gestión de operaciones - Estilo minimalista
    wxStaticBox* uploadControlStaticBox = new wxStaticBox(m_mainPanel, wxID_ANY, "Controls");
    uploadControlStaticBox->SetForegroundColour(*wxWHITE);
    wxStaticBoxSizer* uploadControlBox = new wxStaticBoxSizer(uploadControlStaticBox, wxHORIZONTAL);
    
    m_pauseButton = new wxButton(m_mainPanel, ID_PAUSE_UPLOAD, "Pause");
    m_resumeButton = new wxButton(m_mainPanel, ID_RESUME_UPLOAD, "Resume");
    m_stopButton = new wxButton(m_mainPanel, ID_STOP_UPLOAD, "Stop");
    m_cancelButton = new wxButton(m_mainPanel, ID_CANCEL_UPLOAD, "Cancel");
    m_showIncompleteButton = new wxButton(m_mainPanel, ID_SHOW_INCOMPLETE, "Show Pending");
    
    // Estilo minimalista - color neutro por defecto
    wxColour btnBg(60, 60, 60);
    wxColour btnFg(200, 200, 200);
    
    m_pauseButton->SetBackgroundColour(btnBg);
    m_pauseButton->SetForegroundColour(btnFg);
    m_resumeButton->SetBackgroundColour(btnBg);
    m_resumeButton->SetForegroundColour(btnFg);
    m_stopButton->SetBackgroundColour(btnBg);
    m_stopButton->SetForegroundColour(btnFg);
    m_cancelButton->SetBackgroundColour(btnBg);
    m_cancelButton->SetForegroundColour(btnFg);
    m_showIncompleteButton->SetBackgroundColour(btnBg);
    m_showIncompleteButton->SetForegroundColour(btnFg);
    
    // Deshabilitados por defecto
    m_pauseButton->Enable(false);
    m_resumeButton->Enable(false);
    m_stopButton->Enable(false);
    m_cancelButton->Enable(false);
    
    uploadControlBox->Add(m_pauseButton, 0, wxALL, 5);
    uploadControlBox->Add(m_resumeButton, 0, wxALL, 5);
    uploadControlBox->Add(m_stopButton, 0, wxALL, 5);
    uploadControlBox->Add(m_cancelButton, 0, wxALL, 5);
    uploadControlBox->AddStretchSpacer();
    uploadControlBox->Add(m_showIncompleteButton, 0, wxALL, 5);
    
    filesBox->Add(m_filesListCtrl, 1, wxEXPAND | wxALL, 5);
    filesBox->Add(filesButtonsSizer, 0, wxALL, 5);
    filesBox->Add(progressSizer, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 5);
    filesBox->Add(uploadControlBox, 0, wxEXPAND | wxALL, 5);
    
    mainSizer->Add(filesBox, 1, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 10);
    
    // === Stats Section ===
    wxStaticBoxSizer* statsBox = new wxStaticBoxSizer(wxHORIZONTAL, m_mainPanel, "Storage & Status");
    wxStaticBox* statsBoxCtrl = statsBox->GetStaticBox();
    statsBoxCtrl->SetFont(wxFont(10, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    statsBoxCtrl->SetForegroundColour(*wxWHITE);
    
    m_totalFilesLabel = new wxStaticText(m_mainPanel, wxID_ANY, "Files: 0");
    m_totalFilesLabel->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    m_totalFilesLabel->SetForegroundColour(*wxWHITE);
    
    m_totalStorageLabel = new wxStaticText(m_mainPanel, wxID_ANY, "Storage: 0 MB");
    m_totalStorageLabel->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    m_totalStorageLabel->SetForegroundColour(*wxWHITE);
    
    m_serverStatusLabel = new wxStaticText(m_mainPanel, wxID_ANY, "Status: Connected");
    m_serverStatusLabel->SetFont(wxFont(9, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    m_serverStatusLabel->SetForegroundColour(*wxWHITE);
    
    m_contactButton = new wxButton(m_mainPanel, ID_CONTACT_BUTTON, "Contact", wxDefaultPosition, wxSize(130, 32));
    m_contactButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_contactButton->SetForegroundColour(*wxWHITE);
    
    m_communityButton = new wxButton(m_mainPanel, ID_COMMUNITY_BUTTON, "Community", wxDefaultPosition, wxSize(130, 32));
    m_communityButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_communityButton->SetForegroundColour(*wxWHITE);
    
    // Protected contact link (hidden but validated)
    m_contactLink = new wxHyperlinkCtrl(m_mainPanel, wxID_ANY, "", "https://t.me/Brainagi", wxDefaultPosition, wxDefaultSize, wxHL_DEFAULT_STYLE);
    m_contactLink->Hide(); // Ocultar completamente el enlace
    
    statsBox->Add(m_totalFilesLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 10);
    statsBox->AddSpacer(20);
    statsBox->Add(m_totalStorageLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 10);
    statsBox->AddSpacer(20);
    statsBox->Add(m_serverStatusLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 10);
    statsBox->AddStretchSpacer();
    statsBox->Add(m_communityButton, 0, wxALL, 5);
    statsBox->Add(m_contactButton, 0, wxALL, 5);
    
    mainSizer->Add(statsBox, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 10);
    
    m_mainPanel->SetSizer(mainSizer);
}

void MainWindow::OnUploadFile(wxCommandEvent& event) {
    if (!m_configValid) {
        wxMessageBox("Please configure your Telegram credentials first using the Config menu.", 
                    "Configuration Required", wxOK | wxICON_WARNING);
        return;
    }
    
    LOG_INFO("Upload file dialog opened");
    
    wxFileDialog openFileDialog(this, "Choose a file", "", "",
                                "All files (*.*)|*.*",
                                wxFD_OPEN | wxFD_FILE_MUST_EXIST);
    
    if (openFileDialog.ShowModal() == wxID_CANCEL) {
        LOG_INFO("Upload canceled by user");
        return;
    }
    
    wxString path = openFileDialog.GetPath();
    std::string filePath = path.ToStdString();
    
    LOG_INFO("File selected for upload: " + filePath);
    
    // Verificar tamaño del archivo
    std::ifstream file(filePath, std::ios::binary | std::ios::ate);
    int64_t fileSize = 0;
    if (file.is_open()) {
        fileSize = static_cast<int64_t>(file.tellg());
    }
    file.close();
    
    if (!m_telegramHandler) {
        LOG_ERROR("TelegramHandler not initialized");
        wxMessageBox("TelegramHandler not initialized", "Error", wxOK | wxICON_ERROR);
        return;
    }
    
    // Mostrar barra de progreso y iniciar animación de puntos
    m_uploadProgress->SetRange(100);
    m_uploadProgress->SetValue(0);
    m_uploadProgress->Show();
    m_uploadStatusLabel->SetLabel("Uploading");
    m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
    
    // Iniciar animación de puntos de manera segura
    if (m_animationTimer) {
        m_showDotAnimation = true;
        m_dotAnimationCounter = 0;
        m_animationTimer->Start(500); // Actualizar cada 500ms
    }
    SetStatusText("Uploading: " + path);
    
    Config& config = Config::instance();
    
    // Verificar si se debe encriptar
    bool shouldEncrypt = m_encryptFilesCheckBox->GetValue();
    std::string encryptionPassword;
    std::string actualFilePath = filePath;
    std::string tempEncryptedPath;
    
    if (shouldEncrypt) {
        // Solicitar contraseña
        wxString password = wxGetPasswordFromUser(
            "Enter encryption password for this file:",
            "Encrypt File",
            "",
            this
        );
        
        if (password.IsEmpty()) {
            m_uploadProgress->Hide();
            m_uploadStatusLabel->SetLabel("Upload canceled");
            if (m_animationTimer) {
                m_animationTimer->Stop();
            }
            return;
        }
        
        encryptionPassword = std::string(password.mb_str());
    }
    
    // Ejecutar upload en thread separado para no bloquear UI
    std::thread uploadThread([this, filePath, fileSize, path, shouldEncrypt, encryptionPassword]() {
        Config& config = Config::instance();
        std::string actualFilePath = filePath;
        std::string tempEncryptedPath;
        bool needsCleanup = false;
        
        // Encriptar archivo si es necesario
        if (shouldEncrypt) {
            // Crear archivo temporal encriptado
            tempEncryptedPath = filePath + ".tmp";
            
            LOG_INFO("Encrypting file before upload...");
            
            if (!encryptFile(filePath, tempEncryptedPath, encryptionPassword)) {
                wxTheApp->CallAfter([this]() {
                    if (m_showDotAnimation && m_animationTimer) {
                        m_showDotAnimation = false;
                        m_animationTimer->Stop();
                    }
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->SetLabel("Encryption failed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                    wxMessageBox("Failed to encrypt file.", "Encryption Error", wxOK | wxICON_ERROR);
                });
                return;
            }
            
            actualFilePath = tempEncryptedPath;
            needsCleanup = true;
            LOG_INFO("File encrypted successfully, uploading encrypted version");
        }
        
        if (fileSize > config.chunkThreshold()) {
            LOG_INFO("File size (" + std::to_string(fileSize) + " bytes) exceeds threshold. Using chunked upload.");
            
            // Usar ChunkedUpload
            ChunkedUpload chunkedUploader(m_database.get(), m_telegramHandler.get());
            
            // Configurar callback de progreso en tiempo real
            chunkedUploader.setProgressCallback([this](int completed, int total, double percent) {
                // Actualizar UI en thread principal
                wxTheApp->CallAfter([this, completed, total, percent]() {
                    // Detener animación cuando empiece a mostrar porcentaje
                    if (m_showDotAnimation && m_animationTimer) {
                        m_showDotAnimation = false;
                        m_animationTimer->Stop();
                    }
                    
                    m_uploadProgress->SetValue((int)percent);
                    m_uploadStatusLabel->SetLabel(
                         wxString::Format("Uploading: %d%% (%d/%d chunks)", (int)percent, completed, total)
                    );
                    m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                });
            });
            
            std::string uploadId = chunkedUploader.startUpload(actualFilePath);
            
            // Limpiar archivo temporal si existe
            if (needsCleanup && !tempEncryptedPath.empty()) {
                try {
                    std::filesystem::remove(tempEncryptedPath);
                    LOG_INFO("Temporary encrypted file removed: " + tempEncryptedPath);
                } catch (const std::exception& e) {
                    LOG_WARNING("Failed to remove temporary file: " + std::string(e.what()));
                }
            }
            
            // Actualizar UI en thread principal
            wxTheApp->CallAfter([this, uploadId, path, fileSize, filePath, shouldEncrypt]() {
                // Detener animación
                if (m_showDotAnimation && m_animationTimer) {
                    m_showDotAnimation = false;
                    m_animationTimer->Stop();
                }
                
                if (!uploadId.empty()) {
                    m_uploadProgress->SetValue(100);
                    m_uploadStatusLabel->SetLabel("Upload completed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                    
                    // Guardar archivo en base de datos
                    FileInfo fileInfo;
                    fileInfo.fileId = uploadId;
                    
                    // Extraer nombre de archivo desde wxString (maneja UTF-8 correctamente)
                    wxFileName wxfn(path);
                    fileInfo.fileName = wxfn.GetFullName().ToStdString();
                    
                    fileInfo.fileSize = fileSize;
                    fileInfo.mimeType = detectMimeType(path);
                    fileInfo.category = "chunked";
                    fileInfo.messageId = 0; // Chunked upload no tiene un solo message_id
                    fileInfo.telegramFileId = uploadId;
                    fileInfo.uploaderBotToken = m_telegramHandler->getMainBotToken();
                    fileInfo.isEncrypted = shouldEncrypt;
                    
                    if (m_database->saveFileInfo(fileInfo)) {
                        LOG_INFO("File info saved to database");
                    } else {
                        LOG_WARNING("Failed to save file info to database");
                    }
                    
                    // Recargar lista de archivos
                    LoadFiles();
                    UpdateStats();
                } else {
                    m_uploadStatusLabel->SetLabel("Upload failed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                    wxMessageBox("Upload failed\n\nCheck logs for details.",
                                "Upload Failed", wxOK | wxICON_ERROR);
                }
                
                wxSleep(2);
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
            });
            
        } else {
            LOG_INFO("File size (" + std::to_string(fileSize) + " bytes) below threshold. Using direct upload.");
            
            // Obtener el token del bot que se va a usar
            std::string botToken = m_telegramHandler->getNextBotToken();
            UploadResult result = m_telegramHandler->uploadDocumentWithToken(actualFilePath, botToken);
            
            // Limpiar archivo temporal si existe
            if (needsCleanup && !tempEncryptedPath.empty()) {
                try {
                    std::filesystem::remove(tempEncryptedPath);
                    LOG_INFO("Temporary encrypted file removed: " + tempEncryptedPath);
                } catch (const std::exception& e) {
                    LOG_WARNING("Failed to remove temporary file: " + std::string(e.what()));
                }
            }
            
            // Actualizar UI en thread principal
            wxTheApp->CallAfter([this, result, path, filePath, fileSize, botToken, shouldEncrypt]() {
                // Detener animación
                if (m_showDotAnimation && m_animationTimer) {
                    m_showDotAnimation = false;
                    m_animationTimer->Stop();
                }
                
                if (result.success) {
                    m_uploadProgress->SetValue(100);
                    m_uploadStatusLabel->SetLabel("Upload completed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                    
                    // Guardar archivo en base de datos
                    FileInfo fileInfo;
                    fileInfo.fileId = result.fileId;
                    
                    // Extraer nombre de archivo desde wxString (maneja UTF-8 correctamente)
                    wxFileName wxfn(path);
                    fileInfo.fileName = wxfn.GetFullName().ToStdString();
                    
                    fileInfo.fileSize = fileSize;
                    fileInfo.mimeType = detectMimeType(path);
                    fileInfo.category = "file";
                    fileInfo.messageId = result.messageId;
                    fileInfo.telegramFileId = result.fileId;
                    fileInfo.uploaderBotToken = botToken;
                    fileInfo.isEncrypted = shouldEncrypt;
                    
                    if (m_database->saveFileInfo(fileInfo)) {
                        LOG_INFO("File info saved to database");
                    } else {
                        LOG_WARNING("Failed to save file info to database");
                    }
                    
                    
                    LoadFiles();
                    UpdateStats();
                } else {
                    m_uploadStatusLabel->SetLabel("Upload failed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                    wxMessageBox("Upload failed\n\nCheck logs for details.",
                                "Upload Failed", wxOK | wxICON_ERROR);
                }
                
                wxSleep(2);
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
            });
        }
    });
    
    // Detach thread para que corra independientemente
    uploadThread.detach();
    
    // UI permanece responsiva mientras sube
    LOG_INFO("Upload started in background thread");
}

void MainWindow::OnUploadMultiple(wxCommandEvent& event) {
    if (!m_configValid) {
        wxMessageBox("Please configure your Telegram credentials first using the Config menu.", 
                    "Configuration Required", wxOK | wxICON_WARNING);
        return;
    }
    
    wxFileDialog openFileDialog(this, "Choose files", "", "",
                                "All files (*.*)|*.*",
                                wxFD_OPEN | wxFD_FILE_MUST_EXIST | wxFD_MULTIPLE);
    
    if (openFileDialog.ShowModal() == wxID_CANCEL) {
        LOG_INFO("Upload canceled by user");
        return;
    }
    
    wxArrayString paths;
    openFileDialog.GetPaths(paths);
    
    if (paths.IsEmpty()) {
        LOG_WARNING("No files selected for upload");
        wxMessageBox("No files selected", "Upload", wxOK | wxICON_INFORMATION);
        return;
    }
    
    LOG_INFO("Starting multiple file upload with " + std::to_string(paths.GetCount()) + " files");
    
    // Verificar si se debe encriptar
    bool shouldEncrypt = m_encryptFilesCheckBox->GetValue();
    std::string encryptionPassword;
    
    if (shouldEncrypt) {
        // Solicitar contraseña una sola vez para todos los archivos
        wxString password = wxGetPasswordFromUser(
            "Enter encryption password for all files:",
            "Encrypt Files",
            "",
            this
        );
        
        if (password.IsEmpty()) {
            LOG_INFO("Upload canceled - no password provided");
            return;
        }
        
        encryptionPassword = std::string(password.mb_str());
    }
    
    // Mostrar progreso
    m_uploadProgress->Show();
    m_uploadProgress->SetValue(0);
    m_uploadStatusLabel->SetLabel("Uploading files...");
    
    // Ejecutar subida múltiple en thread separado
    std::thread([this, paths, shouldEncrypt, encryptionPassword]() {
        int totalFiles = paths.GetCount();
        int successfulUploads = 0;
        int failedUploads = 0;
        
        for (int i = 0; i < totalFiles; ++i) {
            wxString path = paths[i];
            std::string filePath = path.ToStdString();
            
            // Actualizar progreso
            int progress = (i * 100) / totalFiles;
            wxTheApp->CallAfter([this, progress, i, totalFiles]() {
                m_uploadProgress->SetValue(progress);
                m_uploadStatusLabel->SetLabel(wxString::Format("Uploading file %d/%d...", i + 1, totalFiles));
                m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
            });
            
            try {
                // Verificar que el archivo existe
                if (!wxFileExists(path)) {
                    LOG_ERROR("File does not exist: " + filePath);
                    failedUploads++;
                    continue;
                }
                
                // Obtener tamaño del archivo
                wxULongLong fileSize = wxFileName::GetSize(path);
                if (fileSize == wxInvalidSize) {
                    LOG_ERROR("Cannot get file size: " + filePath);
                    failedUploads++;
                    continue;
                }
                
                LOG_INFO("Uploading file " + std::to_string(i + 1) + "/" + std::to_string(totalFiles) + ": " + filePath);
                
                // Encriptar archivo si es necesario
                std::string actualFilePath = filePath;
                std::string tempEncryptedPath;
                bool needsCleanup = false;
                
                if (shouldEncrypt) {
                    tempEncryptedPath = filePath + ".tmp";
                    
                    LOG_INFO("Encrypting file: " + filePath);
                    
                    if (!encryptFile(filePath, tempEncryptedPath, encryptionPassword)) {
                        LOG_ERROR("Failed to encrypt file: " + filePath);
                        failedUploads++;
                        continue;
                    }
                    
                    actualFilePath = tempEncryptedPath;
                    needsCleanup = true;
                    LOG_INFO("File encrypted successfully");
                }
                
                // Determinar si usar upload directo o chunked
                Config& config = Config::instance();
                if (fileSize.ToULong() > config.chunkThreshold()) {
                    LOG_INFO("File size (" + std::to_string(fileSize.ToULong()) + " bytes) above threshold. Using chunked upload.");
                    
                    // Chunked upload
                    ChunkedUpload chunkedUpload(m_database.get(), m_telegramHandler.get(), m_telegramNotifier.get());
                    std::string uploadId = chunkedUpload.startUpload(actualFilePath);
                    
                    // Limpiar archivo temporal
                    if (needsCleanup && !tempEncryptedPath.empty()) {
                        try {
                            std::filesystem::remove(tempEncryptedPath);
                            LOG_INFO("Temporary encrypted file removed: " + tempEncryptedPath);
                        } catch (const std::exception& e) {
                            LOG_WARNING("Failed to remove temporary file: " + std::string(e.what()));
                        }
                    }
                    
                    if (!uploadId.empty()) {
                        // Guardar información del archivo
                        FileInfo fileInfo;
                        fileInfo.fileId = uploadId;
                        
                        wxFileName wxfn(path);
                        fileInfo.fileName = wxfn.GetFullName().ToStdString();
                        fileInfo.fileSize = fileSize.ToULong();
                        fileInfo.mimeType = detectMimeType(path);
                        fileInfo.category = "chunked";
                        fileInfo.messageId = 0;
                        fileInfo.telegramFileId = uploadId;
                        fileInfo.uploaderBotToken = m_telegramHandler->getMainBotToken();
                        fileInfo.isEncrypted = shouldEncrypt;
                        
                        if (m_database->saveFileInfo(fileInfo)) {
                            successfulUploads++;
                            LOG_INFO("Chunked upload successful: " + filePath);
                        } else {
                            failedUploads++;
                            LOG_ERROR("Failed to save chunked file info: " + filePath);
                        }
                    } else {
                        failedUploads++;
                        LOG_ERROR("Chunked upload failed: " + filePath);
                    }
                } else {
                    LOG_INFO("File size (" + std::to_string(fileSize.ToULong()) + " bytes) below threshold. Using direct upload.");
                    
                    // Direct upload
                    std::string botToken = m_telegramHandler->getNextBotToken();
                    UploadResult result = m_telegramHandler->uploadDocumentWithToken(actualFilePath, botToken);
                    
                    // Limpiar archivo temporal
                    if (needsCleanup && !tempEncryptedPath.empty()) {
                        try {
                            std::filesystem::remove(tempEncryptedPath);
                            LOG_INFO("Temporary encrypted file removed: " + tempEncryptedPath);
                        } catch (const std::exception& e) {
                            LOG_WARNING("Failed to remove temporary file: " + std::string(e.what()));
                        }
                    }
                    
                    if (result.success) {
                        // Guardar información del archivo
                        FileInfo fileInfo;
                        fileInfo.fileId = result.fileId;
                        
                        wxFileName wxfn(path);
                        fileInfo.fileName = wxfn.GetFullName().ToStdString();
                        fileInfo.fileSize = fileSize.ToULong();
                        fileInfo.mimeType = detectMimeType(path);
                        fileInfo.category = "file";
                        fileInfo.messageId = result.messageId;
                        fileInfo.telegramFileId = result.fileId;
                        fileInfo.uploaderBotToken = botToken;
                        fileInfo.isEncrypted = shouldEncrypt;
                        
                        if (m_database->saveFileInfo(fileInfo)) {
                            successfulUploads++;
                            LOG_INFO("Direct upload successful: " + filePath);
                        } else {
                            failedUploads++;
                            LOG_ERROR("Failed to save direct file info: " + filePath);
                        }
                    } else {
                        failedUploads++;
                        LOG_ERROR("Direct upload failed: " + filePath);
                    }
                }
                
            } catch (const std::exception& e) {
                LOG_ERROR("Exception during upload of " + filePath + ": " + e.what());
                failedUploads++;
            }
        }
        
        // Actualizar UI final
        wxTheApp->CallAfter([this, totalFiles, successfulUploads, failedUploads]() {
            m_uploadProgress->SetValue(100);
            
            if (failedUploads == 0) {
                m_uploadStatusLabel->SetLabel("All uploads completed successfully");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                wxMessageBox(wxString::Format("Upload completed!\n\nFiles uploaded: %d\nAll successful!", totalFiles),
                            "Upload Completed", wxOK | wxICON_INFORMATION);
            } else {
                m_uploadStatusLabel->SetLabel("Some uploads failed");
                m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                wxMessageBox(wxString::Format("Upload completed with errors!\n\nTotal files: %d\nSuccessful: %d\nFailed: %d", 
                                            totalFiles, successfulUploads, failedUploads),
                            "Upload Completed", wxOK | wxICON_WARNING);
            }
            
            // Recargar lista y estadísticas
            LoadFiles();
            UpdateStats();
            
            // Ocultar progreso después de un momento
            wxSleep(2);
            m_uploadProgress->Hide();
            m_uploadStatusLabel->SetLabel("Ready");
            m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
        });
        
    }).detach();
}

void MainWindow::OnRefresh(wxCommandEvent& event) {
    LoadFiles();
    UpdateStats();
    SetStatusText("Files refreshed");
}

void MainWindow::OnDownload(wxCommandEvent& event) {
    // Verificar si hay archivos seleccionados
    if (m_selectedItems.empty()) {
        LOG_WARNING("Download attempted without file selection");
        wxMessageBox("Please select one or more files to download",
                    "Download", wxOK | wxICON_INFORMATION);
        return;
    }
    
    // Si hay múltiples archivos seleccionados, usar descarga por lotes
    if (m_selectedItems.size() > 1) {
        LOG_INFO("Multiple files selected (" + std::to_string(m_selectedItems.size()) + "), using batch download");
        
        // Seleccionar directorio de destino
        wxDirDialog dirDialog(this, "Choose download location for " + std::to_string(m_selectedItems.size()) + " files", 
                             "", wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
        
        if (dirDialog.ShowModal() == wxID_CANCEL) {
            LOG_INFO("Batch download canceled by user");
            return;
        }
        
        wxString destDir = dirDialog.GetPath();
        
        // Mostrar progreso
        m_uploadProgress->SetValue(0);
        m_uploadProgress->Show();
        m_uploadStatusLabel->SetLabel("Downloading files...");
        m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
        
        // Ejecutar descarga por lotes en hilo separado
        std::thread downloadThread([this, destDir]() {
            try {
                BatchProgressCallback progressCallback = [this](int current, int total, const std::string& operation, const std::string& currentFile) {
                    wxTheApp->CallAfter([this, current, total, operation, currentFile]() {
                        int progress = (current * 100) / total;
                        m_uploadProgress->SetValue(progress);
                        m_uploadStatusLabel->SetLabel(
                            wxString::Format("%s %d/%d: %s", operation, current, total, wxString::FromUTF8(currentFile))
                        );
                        m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                    });
                };
                
                bool success = m_batchOperations->downloadFiles(m_selectedItems, m_itemToFileId, 
                                                               std::string(destDir.mb_str()), "", progressCallback);
                
                wxTheApp->CallAfter([this, success]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    
                    // Limpiar selección
                    for (long index : m_selectedItems) {
                        m_filesListCtrl->SetItem(index, 0, "[ ]");
                    }
                    m_selectedItems.clear();
                    
                    if (success) {
                        wxMessageBox("All files downloaded successfully!", "Batch Download Complete", wxOK | wxICON_INFORMATION);
                    } else {
                        wxMessageBox("Some files failed to download. Check logs for details.", "Batch Download Complete", wxOK | wxICON_WARNING);
                    }
                });
                
            } catch (const std::exception& e) {
                LOG_CRITICAL("Exception in batch download thread: " + std::string(e.what()));
                wxTheApp->CallAfter([this]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    wxMessageBox("Batch download failed with error. Check logs for details.", "Error", wxOK | wxICON_ERROR);
                });
            }
        });
        
        downloadThread.detach();
        LOG_INFO("Batch download started in background thread");
        return;
    }
    
    // Descarga individual (código original)
    long selected = *m_selectedItems.begin();
    if (selected == -1) {
        LOG_WARNING("Download attempted without file selection");
        wxMessageBox("Please select a file to download",
                    "Download", wxOK | wxICON_INFORMATION);
        return;
    }
    
    // Obtener file_id del mapeo seguro
    auto it = m_itemToFileId.find(selected);
    if (it == m_itemToFileId.end()) {
        LOG_ERROR("No file ID found for selected item index: " + std::to_string(selected));
        wxMessageBox("File ID not found. Try refreshing the list.", "Download Error", wxOK | wxICON_ERROR);
        return;
    }
    
    std::string fileId = it->second;
    LOG_DEBUG("File ID: " + fileId);
    
    // Obtener info del archivo desde DB para verificar si es chunked
    FileInfo fileInfo;
    try {
        fileInfo = m_database->getFileInfo(fileId);
        
        if (fileInfo.fileId.empty()) {
            LOG_ERROR("File not found in database: " + fileId);
            wxMessageBox("File not found in database.\n\nTry clicking Refresh.",
                        "Download Error", wxOK | wxICON_ERROR);
            return;
        }
        
        LOG_INFO("File info retrieved: " + fileInfo.fileName + ", Category: " + fileInfo.category);
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception getting file info: " + std::string(e.what()));
        wxMessageBox("Error retrieving file information.\n\nCheck logs for details.",
                    "Download Error", wxOK | wxICON_ERROR);
        return;
    }
    
    // Usar el nombre de la base de datos (sin emoji)
    wxString fileName = wxString::FromUTF8(fileInfo.fileName);
    
    // Seleccionar directorio de destino
    wxDirDialog dirDialog(this, "Choose download location", "",
                         wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
    
    if (dirDialog.ShowModal() == wxID_CANCEL) {
        LOG_INFO("Download canceled by user");
        return;
    }
    
    wxString destDir = dirDialog.GetPath();
    wxString destPath = destDir + wxFileName::GetPathSeparator() + fileName;
    
    LOG_INFO("Download destination: " + destPath.ToStdString());
    
    // Verificar si el archivo está encriptado
    std::string decryptionPassword;
    if (fileInfo.isEncrypted) {
        wxString password = wxGetPasswordFromUser(
            "This file is encrypted. Enter the decryption password:",
            "Decrypt File",
            "",
            this
        );
        
        if (password.IsEmpty()) {
            LOG_INFO("Download canceled - no password provided for encrypted file");
            return;
        }
        
        decryptionPassword = std::string(password.mb_str());
        LOG_INFO("Password provided for encrypted file");
    }
    
    if (!m_telegramHandler) {
        LOG_ERROR("TelegramHandler not initialized");
        return;
    }
    
    // Mostrar progreso
    m_uploadProgress->SetValue(0);
    m_uploadProgress->Show();
    m_uploadStatusLabel->SetLabel("Downloading...");
    m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
    SetStatusText("Downloading: " + fileName);
    
    // Establecer controles de operación
    UpdateOperationControls(true, OperationType::DOWNLOAD);
    
    // Download en thread separado
    bool isEncrypted = fileInfo.isEncrypted;
    std::thread downloadThread([this, fileId, fileInfo, destPath, fileName, isEncrypted, decryptionPassword]() {
        try {
            bool success = false;
            
            LOG_INFO("Download thread started for: " + fileId);
            
            // Verificar si es archivo chunked o simple
            if (fileInfo.category == "chunked") {
                LOG_INFO("Chunked file detected - using ChunkedDownload with persistence");
                
                // Usar ChunkedDownload con persistencia
                ChunkedDownload chunkedDownloader(m_database.get(), m_telegramHandler.get(), m_telegramNotifier.get());
            
                // Configurar callback de progreso en tiempo real
                chunkedDownloader.setProgressCallback([this](int64_t completed, int64_t total, double percent) {
                    // Actualizar UI en thread principal
                    wxTheApp->CallAfter([this, completed, total, percent]() {
                        m_uploadProgress->SetValue((int)percent);
                        
                        // Valores negativos indican reconstrucción
                        if (completed < 0 && total < 0) {
                            int64_t reconstructed = -completed;
                            int64_t totalToReconstruct = -total;
                            m_uploadStatusLabel->SetLabel(
                                wxString::Format("Reconstructing file: %d%% (%lld/%lld chunks)", 
                                               (int)percent, reconstructed, totalToReconstruct)
                            );
                            m_uploadStatusLabel->SetForegroundColour(wxColour(255, 200, 0));
                        } else {
                            m_uploadStatusLabel->SetLabel(
                                wxString::Format("Downloading: %d%% (%lld/%lld chunks)", (int)percent, completed, total)
                            );
                            m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                        }
                    });
                });
                
                std::string downloadId = chunkedDownloader.startDownload(fileId, destPath.ToStdString());
                
                // Guardar download ID
                wxTheApp->CallAfter([this, downloadId]() {
                    m_currentDownloadId = downloadId;
                });
                
                if (!downloadId.empty()) {
                    LOG_INFO("Download completed successfully: " + downloadId);
            
            // Desencriptar si es necesario
            if (isEncrypted) {
                LOG_INFO("Decrypting downloaded file...");
                
                std::string tempEncryptedPath = destPath.ToStdString() + ".tmp";
                
                // Renombrar archivo descargado a temporal
                try {
                    std::filesystem::rename(destPath.ToStdString(), tempEncryptedPath);
                } catch (const std::exception& e) {
                    LOG_ERROR("Failed to rename file for decryption: " + std::string(e.what()));
                    wxTheApp->CallAfter([this]() {
                        m_uploadProgress->Hide();
                        wxMessageBox("Failed to prepare file for decryption.", "Error", wxOK | wxICON_ERROR);
                    });
                    return;
                }
                
                // Desencriptar
                if (!decryptFile(tempEncryptedPath, destPath.ToStdString(), decryptionPassword)) {
                    LOG_ERROR("Failed to decrypt downloaded file");
                    
                    // Restaurar archivo encriptado
                    try {
                        std::filesystem::rename(tempEncryptedPath, destPath.ToStdString());
                    } catch (...) {}
                    
                    wxTheApp->CallAfter([this]() {
                        m_uploadProgress->Hide();
                        wxMessageBox("Failed to decrypt file. Wrong password?", "Decryption Error", wxOK | wxICON_ERROR);
                    });
                    return;
                }
                
                // Eliminar archivo temporal encriptado
                try {
                    std::filesystem::remove(tempEncryptedPath);
                } catch (...) {}
                
                LOG_INFO("File decrypted successfully");
            }
            
                    wxTheApp->CallAfter([this, fileName, destPath]() {
                m_uploadProgress->SetValue(100);
                m_uploadStatusLabel->SetLabel("Download completed");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                
                wxString msg = wxString::Format(
                    "Download Successful\n\n"
                    "File: %s\n"
                            "Location: %s\n\n"
                    "Check logs/telegram_cloud_*.txt for details.",
                            fileName, destPath
                );
                
                wxMessageBox(msg, "Download Completed", wxOK | wxICON_INFORMATION);
                
                wxSleep(2);
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                UpdateOperationControls(false);
                m_currentDownloadId.clear();
            });
            
                    success = true;
                } else {
                    LOG_ERROR("Download failed");
                    success = false;
                }
        } else {
            // Archivo simple, download directo
            LOG_INFO("Simple file - direct download");
            
            wxTheApp->CallAfter([this]() {
                m_uploadProgress->Pulse();
            });
            
            success = m_telegramHandler->downloadFile(fileInfo.telegramFileId, destPath.ToStdString());
            
            // Desencriptar si es necesario
            if (success && isEncrypted) {
                LOG_INFO("Decrypting downloaded file...");
                
                std::string tempEncryptedPath = destPath.ToStdString() + ".tmp";
                
                // Renombrar archivo descargado a temporal
                try {
                    std::filesystem::rename(destPath.ToStdString(), tempEncryptedPath);
                } catch (const std::exception& e) {
                    LOG_ERROR("Failed to rename file for decryption: " + std::string(e.what()));
                    success = false;
                }
                
                if (success) {
                    // Desencriptar
                    if (!decryptFile(tempEncryptedPath, destPath.ToStdString(), decryptionPassword)) {
                        LOG_ERROR("Failed to decrypt downloaded file");
                        success = false;
                        
                        // Restaurar archivo encriptado
                        try {
                            std::filesystem::rename(tempEncryptedPath, destPath.ToStdString());
                        } catch (...) {}
                    } else {
                        // Eliminar archivo temporal encriptado
                        try {
                            std::filesystem::remove(tempEncryptedPath);
                        } catch (...) {}
                        
                        LOG_INFO("File decrypted successfully");
                    }
                }
            }
            
            wxTheApp->CallAfter([this, success, fileName, destPath]() {
                if (success) {
                    m_uploadProgress->SetValue(100);
                    m_uploadStatusLabel->SetLabel("Download completed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                    
                    wxString msg = wxString::Format(
                        "Download Successful\n\n"
                        "File: %s\n"
                        "Location: %s\n\n"
                        "Check logs/telegram_cloud_*.txt for details.",
                        fileName, destPath
                    );
                    
                    wxMessageBox(msg, "Download Completed", wxOK | wxICON_INFORMATION);
                } else {
                    m_uploadStatusLabel->SetLabel("Download failed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                    
                    wxMessageBox(
                        "Download Failed\n\n"
                        "File: " + fileName + "\n\n"
                        "Check logs/telegram_cloud_*.txt for details.",
                        "Download Failed", wxOK | wxICON_ERROR
                    );
                }
                
                wxSleep(2);
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                UpdateOperationControls(false);
                m_currentDownloadId.clear();
            });
        }
        
        } catch (const std::exception& e) {
            LOG_CRITICAL("Exception in download thread: " + std::string(e.what()));
            
            wxTheApp->CallAfter([this, e]() {
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                UpdateOperationControls(false);
                m_currentDownloadId.clear();
                
                wxString msg = wxString::Format(
                    "Download crashed with exception:\n\n%s\n\n"
                    "Check logs/telegram_cloud_*.txt for details.",
                    e.what()
                );
                
                wxMessageBox(msg, "Download Error", wxOK | wxICON_ERROR);
            });
        } catch (...) {
            LOG_CRITICAL("Unknown exception in download thread");
            
            wxTheApp->CallAfter([this]() {
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                UpdateOperationControls(false);
                m_currentDownloadId.clear();
                wxMessageBox("Download crashed with unknown error.\n\nCheck logs for details.",
                            "Download Error", wxOK | wxICON_ERROR);
            });
        }
    });
    
    downloadThread.detach();
    LOG_INFO("Download started in background thread");
}

void MainWindow::OnDelete(wxCommandEvent& event) {
    // Verificar si hay archivos seleccionados
    if (m_selectedItems.empty()) {
        wxMessageBox("Please select one or more files to delete",
                    "Delete", wxOK | wxICON_INFORMATION);
        return;
    }
    
    // Si hay múltiples archivos seleccionados, usar eliminación por lotes
    if (m_selectedItems.size() > 1) {
        int fileCount = m_selectedItems.size();
        wxString confirmMsg = wxString::Format(
            "Are you sure you want to delete %d files?\n\nThis will permanently remove them from both Telegram and the local database.",
            fileCount
        );
        
        int ret = wxMessageBox(confirmMsg, "Batch Delete Files", wxYES_NO | wxICON_QUESTION);
        
        if (ret == wxYES) {
            LOG_INFO("Starting batch delete operation for " + std::to_string(m_selectedItems.size()) + " files");
            
            // Mostrar progreso
            m_uploadProgress->Show();
            m_uploadProgress->SetValue(0);
            m_uploadStatusLabel->SetLabel("Deleting files...");
            m_uploadStatusLabel->Show();
            
            // Ejecutar eliminación en hilo separado
            std::thread deleteThread([this, fileCount]() {
                try {
                    BatchProgressCallback progressCallback = [this](int current, int total, const std::string& operation, const std::string& currentFile) {
                        wxTheApp->CallAfter([this, current, total, operation, currentFile]() {
                            int progress = (current * 100) / total;
                            m_uploadProgress->SetValue(progress);
                            m_uploadStatusLabel->SetLabel(
                                wxString::Format("%s %d/%d: %s", operation, current, total, wxString::FromUTF8(currentFile))
                            );
                        });
                    };
                    
                    bool success = m_batchOperations->deleteFiles(m_selectedItems, m_itemToFileId, progressCallback);
                    
                    // Actualizar interfaz
                    wxTheApp->CallAfter([this, success, fileCount]() {
                        m_uploadProgress->Hide();
                        m_uploadStatusLabel->Hide();
                        m_uploadStatusLabel->SetLabel("Ready");
                        
                        // Limpiar selección
                        m_selectedItems.clear();
                        
                        // Recargar lista y actualizar estadísticas
                        LoadFiles();
                        UpdateStats();
                        
                        // Mensaje de resultado
                        wxString msg;
                        if (success) {
                            msg = wxString::Format(
                                "Files deleted successfully!\n\n"
                                "Total: %d files deleted",
                                fileCount
                            );
                            wxMessageBox(msg, "Batch Delete Successful", wxOK | wxICON_INFORMATION);
                        } else {
                            msg = wxString::Format(
                                "Batch delete completed with errors\n\n"
                                "Some files may not have been deleted\n\n"
                                "Check logs for details."
                            );
                            wxMessageBox(msg, "Batch Delete Completed", wxOK | wxICON_WARNING);
                        }
                        
                        LOG_INFO("Batch delete completed: " + std::to_string(fileCount) + " files");
                    });
                    
                } catch (const std::exception& e) {
                    LOG_CRITICAL("Exception in batch delete thread: " + std::string(e.what()));
                    
                    wxTheApp->CallAfter([this]() {
                        m_uploadProgress->Hide();
                        m_uploadStatusLabel->Hide();
                        wxMessageBox("Batch delete failed with error. Check logs for details.", "Error", wxOK | wxICON_ERROR);
                    });
                }
            });
            
            deleteThread.detach();
            LOG_INFO("Batch delete started in background thread");
        }
        return;
    }
    
    // Eliminación individual (código original)
    long selected = *m_selectedItems.begin();
    
    wxString fileName = m_filesListCtrl->GetItemText(selected, 1); // Columna 1 tiene el nombre
    
    int ret = wxMessageBox("Are you sure you want to delete '" + fileName + "'?\n\nThis will permanently remove the file from both Telegram and the local database.",
                          "Delete File", wxYES_NO | wxICON_QUESTION);
    
    if (ret == wxYES) {
        // Obtener file ID desde el mapeo
        auto it = m_itemToFileId.find(selected);
        if (it == m_itemToFileId.end()) {
            LOG_ERROR("Failed to find file_id for selected item: " + std::to_string(selected));
            LOG_ERROR("Available mappings: " + std::to_string(m_itemToFileId.size()));
            for (const auto& pair : m_itemToFileId) {
                LOG_ERROR("  Index " + std::to_string(pair.first) + " -> " + pair.second);
            }
            wxMessageBox("Failed to get file information. Please refresh and try again.",
                        "Delete Error", wxOK | wxICON_ERROR);
            return;
        }
        
        std::string fileId = it->second;
        LOG_DEBUG("Found file_id for selected item " + std::to_string(selected) + ": " + fileId);
        
        LOG_INFO("Starting delete operation for file: '" + fileName.ToStdString() + "' (ID: " + fileId + ")");
        
        // Mostrar progreso
        m_uploadProgress->Show();
        m_uploadProgress->SetValue(0);
        m_uploadStatusLabel->SetLabel("Deleting file...");
        m_uploadStatusLabel->Show();
        
        // Ejecutar eliminación en hilo separado
        std::thread deleteThread([this, fileId, fileName, selected]() {
            try {
                bool success = true;
                int deletedCount = 0;
                int totalMessages = 0;
                
                // 1. Obtener mensajes a eliminar de Telegram
                auto messagesToDelete = m_database->getMessagesToDelete(fileId);
                totalMessages = messagesToDelete.size();
                
                LOG_INFO("Found " + std::to_string(totalMessages) + " messages to delete from Telegram");
                
                // 2. Eliminar mensajes de Telegram
                for (const auto& msg : messagesToDelete) {
                    int64_t messageId = msg.first;
                    std::string botToken = msg.second;
                    
                    if (m_telegramHandler->deleteMessage(messageId, botToken)) {
                        deletedCount++;
                        LOG_INFO("Successfully deleted message " + std::to_string(messageId) + " from Telegram");
                    } else {
                        LOG_WARNING("Failed to delete message " + std::to_string(messageId) + " from Telegram");
                        // Continuar con los demás mensajes aunque uno falle
                    }
                    
                    // Actualizar progreso
                    int progress = (deletedCount * 50) / totalMessages; // 50% para eliminación de Telegram
                    wxTheApp->CallAfter([this, progress]() {
                        m_uploadProgress->SetValue(progress);
                    });
                }
                
                // 3. Eliminar de la base de datos
                wxTheApp->CallAfter([this]() {
                    m_uploadStatusLabel->SetLabel("Removing from database...");
                    m_uploadProgress->SetValue(50);
                });
                
                if (!m_database->deleteFile(fileId)) {
                    LOG_ERROR("Failed to delete file from database: " + fileId);
                    success = false;
                }
                
                // 4. Actualizar interfaz
                wxTheApp->CallAfter([this, success, fileName, selected, deletedCount, totalMessages]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    
                    if (success) {
                        // Eliminar de la lista
                        m_filesListCtrl->DeleteItem(selected);
                        
                        // Actualizar estadísticas
                        UpdateStats();
                        
                        // Mensaje de éxito
                        wxString msg = wxString::Format(
                            "File deleted successfully!\n\n"
                            "File: %s\n"
                            "Messages deleted from Telegram: %d/%d",
                            fileName, deletedCount, totalMessages
                        );
                        
                        wxMessageBox(msg, "Delete Successful", wxOK | wxICON_INFORMATION);
                        SetStatusText("Deleted: " + fileName);
                        
                        LOG_INFO("Delete operation completed successfully for file: " + fileName.ToStdString());
                    } else {
                        wxString msg = wxString::Format(
                            "Delete operation failed!\n\n"
                            "File: %s\n"
                            "Messages deleted from Telegram: %d/%d\n\n"
                            "Check logs for details.",
                            fileName, deletedCount, totalMessages
                        );
                        
                        wxMessageBox(msg, "Delete Failed", wxOK | wxICON_ERROR);
                        SetStatusText("Delete failed: " + fileName);
                        
                        LOG_ERROR("Delete operation failed for file: " + fileName.ToStdString());
                    }
                });
                
            } catch (const std::exception& e) {
                LOG_CRITICAL("Exception in delete thread: " + std::string(e.what()));
                
                wxTheApp->CallAfter([this, fileName, e]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    
                    wxString msg = wxString::Format(
                        "Delete crashed with exception:\n\n%s\n\n"
                        "Check logs for details.",
                        e.what()
                    );
                    
                    wxMessageBox(msg, "Delete Error", wxOK | wxICON_ERROR);
                    SetStatusText("Delete error: " + fileName);
                });
            } catch (...) {
                LOG_CRITICAL("Unknown exception in delete thread");
                
                wxTheApp->CallAfter([this, fileName]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    
                    wxMessageBox("Delete crashed with unknown error.\n\nCheck logs for details.",
                                "Delete Error", wxOK | wxICON_ERROR);
                    SetStatusText("Delete error: " + fileName);
                });
            }
        });
        
        deleteThread.detach();
        LOG_INFO("Delete started in background thread");
    }
}

void MainWindow::OnContactButton(wxCommandEvent& event) {
    LOG_INFO("Contact button clicked - opening Telegram link");
    SetStatusText("Opening contact link...");
    
    // Abrir el enlace de Telegram en el navegador
    if (wxLaunchDefaultBrowser("https://t.me/Brainagi")) {
        SetStatusText("Contact link opened");
        LOG_INFO("Successfully opened contact link");
    } else {
        SetStatusText("Failed to open contact link");
        wxMessageBox("Failed to open contact link.\n\nPlease visit: https://t.me/Brainagi",
                    "Contact", wxOK | wxICON_WARNING);
        LOG_ERROR("Failed to open contact link");
    }
}

void MainWindow::OnCommunityButton(wxCommandEvent& event) {
    LOG_INFO("Community button clicked - opening Telegram group");
    SetStatusText("Opening community link...");
    
    if (wxLaunchDefaultBrowser("https://t.me/+Rf62OJaoi0kzNzhh")) {
        SetStatusText("Community link opened");
        LOG_INFO("Successfully opened community link");
    } else {
        SetStatusText("Failed to open community link");
        wxMessageBox("Failed to open community link.\n\nPlease visit: https://t.me/+Rf62OJaoi0kzNzhh",
                    "Community", wxOK | wxICON_WARNING);
        LOG_ERROR("Failed to open community link");
    }
}

void MainWindow::OnQuit(wxCommandEvent& event) {
    Close(true);
}

void MainWindow::OnAbout(wxCommandEvent& event) {
    wxMessageBox("Telegram Cloud Desktop\nVersion 1.0.0\n\nBuilt with wxWidgets",
                "About", wxOK | wxICON_INFORMATION);
}

void MainWindow::OnHelpGuide(wxCommandEvent& event) {
    HelpDialog dlg(this);
    dlg.ShowModal();
}

void MainWindow::OnDecryptFile(wxCommandEvent& event) {
    // Seleccionar archivo encriptado
    wxFileDialog openFileDialog(this, "Select encrypted file", "", "",
                               "All files (*.*)|*.*", wxFD_OPEN | wxFD_FILE_MUST_EXIST);
    
    if (openFileDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    wxString encryptedFilePath = openFileDialog.GetPath();
    
    // Verificar que el archivo tenga tamaño mínimo y formato correcto
    std::ifstream checkFile(encryptedFilePath.ToStdString(), std::ios::binary | std::ios::ate);
    if (!checkFile) {
        wxMessageBox("Cannot open file for reading.",
                    "Error", wxOK | wxICON_ERROR);
        return;
    }
    
    std::streamsize fileSize = checkFile.tellg();
    checkFile.seekg(0, std::ios::beg);
    
    if (fileSize < 32) {
        checkFile.close();
        wxMessageBox("File is too small to be encrypted with this method.\n\nMinimum size: 32 bytes (salt + IV)",
                    "Invalid File", wxOK | wxICON_ERROR);
        return;
    }
    
    // Leer los primeros bytes para verificar si parece ser un archivo ya desencriptado
    // (detectar magic numbers de formatos comunes)
    std::vector<unsigned char> header(16);
    checkFile.read(reinterpret_cast<char*>(header.data()), 16);
    checkFile.close();
    
    // Detectar formatos de archivo comunes (estos NO deberían estar encriptados)
    bool looksLikeUnencrypted = false;
    std::string detectedFormat;
    
    // PNG
    if (header[0] == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
        looksLikeUnencrypted = true;
        detectedFormat = "PNG image";
    }
    // JPEG
    else if (header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF) {
        looksLikeUnencrypted = true;
        detectedFormat = "JPEG image";
    }
    // PDF
    else if (header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
        looksLikeUnencrypted = true;
        detectedFormat = "PDF document";
    }
    // ZIP/DOCX/XLSX
    else if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
        looksLikeUnencrypted = true;
        detectedFormat = "ZIP/Office document";
    }
    // GIF
    else if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46) {
        looksLikeUnencrypted = true;
        detectedFormat = "GIF image";
    }
    
    if (looksLikeUnencrypted) {
        int result = wxMessageBox(
            wxString::Format("WARNING: This file appears to be an unencrypted %s.\n\n"
                           "If you decrypt it, the result will be corrupted.\n\n"
                           "Are you sure you want to continue?", detectedFormat),
            "File May Not Be Encrypted",
            wxYES_NO | wxNO_DEFAULT | wxICON_WARNING
        );
        
        if (result == wxNO) {
            return;
        }
    }
    
    // Pedir contraseña
    wxString password = wxGetPasswordFromUser(
        "Enter the password to decrypt this file:",
        "Decrypt File",
        "",
        this
    );
    
    if (password.IsEmpty()) {
        return;
    }
    
    // Seleccionar ubicación de salida
    wxFileName inputFile(encryptedFilePath);
    
    // Generar nombre sugerido inteligente
    wxString baseName = inputFile.GetName();
    wxString extension = inputFile.GetExt().Lower();
    wxString suggestedName;
    
    // Si el archivo tiene extensión .tmp, probablemente el nombre real está en el basename
    if (extension == "tmp") {
        // Intentar extraer la extensión real del nombre base
        wxFileName tempName(baseName);
        if (tempName.HasExt()) {
            suggestedName = tempName.GetName() + "_decrypted." + tempName.GetExt();
        } else {
            suggestedName = baseName + "_decrypted";
        }
    } else {
        suggestedName = baseName + "_decrypted." + extension;
    }
    
    wxFileDialog saveFileDialog(this, "Save decrypted file as", "", suggestedName,
                               "All files (*.*)|*.*", wxFD_SAVE | wxFD_OVERWRITE_PROMPT);
    
    if (saveFileDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    wxString outputFilePath = saveFileDialog.GetPath();
    
    // Desencriptar archivo
    std::string inputPath = std::string(encryptedFilePath.mb_str());
    std::string outputPath = std::string(outputFilePath.mb_str());
    std::string pass = std::string(password.mb_str());
    
    if (decryptFile(inputPath, outputPath, pass)) {
        // Verificar que el archivo de salida se creó correctamente
        std::ifstream verifyFile(outputPath, std::ios::binary | std::ios::ate);
        if (!verifyFile || verifyFile.tellg() == 0) {
            wxMessageBox("Decryption failed - output file is empty or invalid.\n\nPossible causes:\n- Wrong password\n- File was not encrypted with this application\n- Corrupted encrypted file",
                        "Decryption Failed", wxOK | wxICON_ERROR);
            verifyFile.close();
            // Eliminar archivo de salida inválido
            std::filesystem::remove(outputPath);
            return;
        }
        verifyFile.close();
        
        wxMessageBox("File decrypted successfully!\n\nSaved to: " + outputFilePath,
                    "Success", wxOK | wxICON_INFORMATION);
    } else {
        wxMessageBox("Failed to decrypt file.\n\nPossible reasons:\n- Wrong password\n- File is corrupted\n- File is not encrypted with this application",
                    "Decryption Failed", wxOK | wxICON_ERROR);
    }
}

std::string MainWindow::detectMimeType(const wxString& filePath) {
    wxFileName filename(filePath);
    wxString ext = filename.GetExt().Lower();
    
    // Mapeo de extensiones comunes a MIME types
    if (ext == "pdf") return "application/pdf";
    if (ext == "txt") return "text/plain";
    if (ext == "doc") return "application/msword";
    if (ext == "docx") return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (ext == "xls") return "application/vnd.ms-excel";
    if (ext == "xlsx") return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if (ext == "ppt") return "application/vnd.ms-powerpoint";
    if (ext == "pptx") return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    if (ext == "jpg" || ext == "jpeg") return "image/jpeg";
    if (ext == "png") return "image/png";
    if (ext == "gif") return "image/gif";
    if (ext == "bmp") return "image/bmp";
    if (ext == "svg") return "image/svg+xml";
    if (ext == "mp4") return "video/mp4";
    if (ext == "avi") return "video/x-msvideo";
    if (ext == "mov") return "video/quicktime";
    if (ext == "wmv") return "video/x-ms-wmv";
    if (ext == "mp3") return "audio/mpeg";
    if (ext == "wav") return "audio/wav";
    if (ext == "flac") return "audio/flac";
    if (ext == "zip") return "application/zip";
    if (ext == "rar") return "application/vnd.rar";
    if (ext == "7z") return "application/x-7z-compressed";
    if (ext == "tar") return "application/x-tar";
    if (ext == "gz") return "application/gzip";
    if (ext == "exe") return "application/x-msdownload";
    if (ext == "msi") return "application/x-msdownload";
    if (ext == "dll") return "application/x-msdownload";
    if (ext == "pyd") return "application/x-python-code";
    if (ext == "py") return "text/x-python";
    if (ext == "cpp" || ext == "c") return "text/x-c++";
    if (ext == "h" || ext == "hpp") return "text/x-c++";
    if (ext == "js") return "application/javascript";
    if (ext == "html" || ext == "htm") return "text/html";
    if (ext == "css") return "text/css";
    if (ext == "xml") return "application/xml";
    if (ext == "json") return "application/json";
    if (ext == "csv") return "text/csv";
    if (ext == "rtf") return "application/rtf";
    if (ext == "odt") return "application/vnd.oasis.opendocument.text";
    if (ext == "ods") return "application/vnd.oasis.opendocument.spreadsheet";
    if (ext == "odp") return "application/vnd.oasis.opendocument.presentation";
    
    // Por defecto
    return "application/octet-stream";
}

void MainWindow::OnAnimationTimer(wxTimerEvent& event) {
    if (!m_showDotAnimation || !m_uploadStatusLabel || !m_animationTimer) {
        return;
    }
    
    m_dotAnimationCounter++;
    if (m_dotAnimationCounter > 3) {
        m_dotAnimationCounter = 1;
    }
    
    // Crear string de puntos de manera segura
    wxString dots = "";
    for (int i = 0; i < m_dotAnimationCounter; i++) {
        dots += ".";
    }
    
    try {
        m_uploadStatusLabel->SetLabel("Uploading" + dots);
        m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
    } catch (...) {
        // En caso de error, detener la animación
        m_showDotAnimation = false;
        if (m_animationTimer) {
            m_animationTimer->Stop();
        }
    }
}

void MainWindow::OnContactValidationTimer(wxTimerEvent& event) {
    // Validación periódica del enlace de contacto
    validateContactLink();
}


void MainWindow::LoadFiles() {
    m_filesListCtrl->DeleteAllItems();
    m_itemToFileId.clear(); // Limpiar mapeo anterior
    m_selectedItems.clear(); // Limpiar selección
    
    if (!m_database) {
        LOG_WARNING("Database not initialized, cannot load files");
        return;
    }
    
    std::vector<FileInfo> files = m_database->getFiles();
    
    // Apply search filter
    if (!m_currentSearch.empty()) {
        std::vector<FileInfo> filteredFiles;
        for (const FileInfo& file : files) {
            std::string fileName = file.fileName;
            std::string searchTerm = m_currentSearch;
            
            // Convert to lowercase for case-insensitive search
            std::transform(fileName.begin(), fileName.end(), fileName.begin(), ::tolower);
            std::transform(searchTerm.begin(), searchTerm.end(), searchTerm.begin(), ::tolower);
            
            if (fileName.find(searchTerm) != std::string::npos) {
                filteredFiles.push_back(file);
            }
        }
        files = filteredFiles;
    }
    
    // Apply sorting
    std::sort(files.begin(), files.end(), [this](const FileInfo& a, const FileInfo& b) {
        bool result = false;
        
        if (m_currentSortBy == "name") {
            result = a.fileName < b.fileName;
        } else if (m_currentSortBy == "size") {
            result = a.fileSize < b.fileSize;
        } else if (m_currentSortBy == "date") {
            result = a.uploadDate < b.uploadDate;
        } else if (m_currentSortBy == "type") {
            result = a.mimeType < b.mimeType;
        } else {
            result = a.fileName < b.fileName; // Default to name
        }
        
        return m_sortAscending ? result : !result;
    });
    
    LOG_INFO("Loading " + std::to_string(files.size()) + " files into UI (search: '" + m_currentSearch + "', sort: " + m_currentSortBy + ")");
    
    for (const FileInfo& file : files) {
        // Agregar candado si está encriptado
        wxString displayName = wxString::FromUTF8(file.fileName);
        if (file.isEncrypted) {
            displayName = wxString::FromUTF8("\xF0\x9F\x94\x92 ") + displayName; // 🔒 emoji
        }
        
        long index = m_filesListCtrl->InsertItem(m_filesListCtrl->GetItemCount(), "[ ]");
        
        // Guardar file_id en mapeo seguro
        m_itemToFileId[index] = file.fileId;
        
        LOG_DEBUG("Mapped file: '" + file.fileName + "' -> ID: " + file.fileId + " at index: " + std::to_string(index));
        
        // Agregar nombre del archivo en columna 1
        m_filesListCtrl->SetItem(index, 1, displayName);
        
        // Formatear tamaño
        double sizeMB = file.fileSize / 1024.0 / 1024.0;
        wxString sizeStr;
        if (sizeMB < 1.0) {
            sizeStr = wxString::Format("%.2f KB", file.fileSize / 1024.0);
        } else if (sizeMB < 1024.0) {
            sizeStr = wxString::Format("%.2f MB", sizeMB);
        } else {
            sizeStr = wxString::Format("%.2f GB", sizeMB / 1024.0);
        }
        
        m_filesListCtrl->SetItem(index, 2, sizeStr);
        // Detectar tipo MIME correcto basado en la extensión del archivo
        wxString correctMimeType = wxString::FromUTF8(detectMimeType(wxString::FromUTF8(file.fileName)));
        m_filesListCtrl->SetItem(index, 3, correctMimeType);
        m_filesListCtrl->SetItem(index, 4, wxString::FromUTF8(file.uploadDate));
    }
    
    LOG_DEBUG("Files loaded successfully into list");
}

void MainWindow::UpdateStats() {
    if (!m_database) {
        return;
    }
    
    int totalFiles = m_database->getTotalFilesCount();
    int64_t totalStorage = m_database->getTotalStorageUsed();
    
    m_totalFilesLabel->SetLabel(wxString::Format("Files: %d", totalFiles));
    
    double storageMB = totalStorage / 1024.0 / 1024.0;
    if (storageMB < 1024.0) {
        m_totalStorageLabel->SetLabel(wxString::Format("Storage: %.2f MB", storageMB));
    } else {
        m_totalStorageLabel->SetLabel(wxString::Format("Storage: %.2f GB", storageMB / 1024.0));
    }
    
    SetStatusText(wxString::Format("Ready - %d files", totalFiles));
    
    LOG_DEBUG("Stats updated: " + std::to_string(totalFiles) + " files, " + 
             std::to_string(totalStorage) + " bytes");
}

// Encriptación/desencriptación de archivos completos
bool MainWindow::encryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password) {
    try {
        // Leer archivo completo
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) {
            LOG_ERROR("Failed to open input file for encryption: " + inputPath);
            return false;
        }
        
        std::vector<char> fileData((std::istreambuf_iterator<char>(inFile)),
                                    std::istreambuf_iterator<char>());
        inFile.close();
        
        // Convertir a string
        std::string plaintext(fileData.begin(), fileData.end());
        
        // Encriptar
        std::string encrypted = aesEncrypt(plaintext, password);
        
        // Escribir archivo encriptado
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to open output file for encryption: " + outputPath);
            return false;
        }
        
        outFile.write(encrypted.c_str(), encrypted.size());
        outFile.close();
        
        LOG_INFO("File encrypted successfully: " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("File encryption failed: " + std::string(e.what()));
        return false;
    }
}

bool MainWindow::decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password) {
    try {
        LOG_INFO("Starting file decryption: " + inputPath);
        
        // Leer archivo encriptado
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) {
            LOG_ERROR("Failed to open input file for decryption: " + inputPath);
            return false;
        }
        
        std::vector<char> fileData((std::istreambuf_iterator<char>(inFile)),
                                    std::istreambuf_iterator<char>());
        inFile.close();
        
        if (fileData.size() < 32) {
            LOG_ERROR("File too small to be encrypted: " + std::to_string(fileData.size()) + " bytes");
            return false;
        }
        
        LOG_INFO("File read successfully, size: " + std::to_string(fileData.size()) + " bytes");
        
        // Convertir a string
        std::string ciphertext(fileData.begin(), fileData.end());
        
        // Desencriptar
        LOG_INFO("Attempting AES decryption...");
        std::string decrypted;
        try {
            decrypted = aesDecrypt(ciphertext, password);
        } catch (const std::exception& e) {
            LOG_ERROR("AES decryption error: " + std::string(e.what()));
            return false;
        }
        
        // Verificar que la desencriptación produjo datos válidos
        if (decrypted.empty()) {
            LOG_ERROR("Decryption produced empty result");
            return false;
        }
        
        LOG_INFO("Decryption successful, output size: " + std::to_string(decrypted.size()) + " bytes");
        
        // Escribir archivo desencriptado
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to open output file for decryption: " + outputPath);
            return false;
        }
        
        outFile.write(decrypted.c_str(), decrypted.size());
        outFile.close();
        
        // Verificar que el archivo se escribió correctamente
        if (!outFile.good()) {
            LOG_ERROR("Error writing decrypted file to disk");
            return false;
        }
        
        LOG_INFO("File decrypted successfully: " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("File decryption failed with exception: " + std::string(e.what()));
        return false;
    }
}

// Simple encryption/decryption functions (similar to Python version)
std::string MainWindow::generateRandomSalt() {
    std::string salt(16, 0);
    if (RAND_bytes(reinterpret_cast<unsigned char*>(&salt[0]), 16) != 1) {
        // Fallback to random_device if OpenSSL fails
        std::random_device rd;
        std::mt19937 gen(rd());
        std::uniform_int_distribution<> dis(0, 255);
        for (int i = 0; i < 16; ++i) {
            salt[i] = static_cast<char>(dis(gen));
        }
    }
    return salt;
}

std::string MainWindow::deriveKey(const std::string& password, const std::string& salt) {
    std::string key(32, 0); // 256 bits = 32 bytes
    
    if (PKCS5_PBKDF2_HMAC(
        password.c_str(), password.length(),
        reinterpret_cast<const unsigned char*>(salt.c_str()), salt.length(),
        10000, // iterations
        EVP_sha256(),
        32,    // key length
        reinterpret_cast<unsigned char*>(&key[0])
    ) != 1) {
        throw std::runtime_error("Key derivation failed");
    }
    
    return key;
}

std::string MainWindow::aesEncrypt(const std::string& plaintext, const std::string& password) {
    // Generar salt aleatorio
    std::string salt = generateRandomSalt();
    
    // Derivar clave de 256 bits
    std::string userKey = deriveKey(password, salt);
    // Mezclar con clave embebida ofuscada
    std::string embedded(ObfuscatedStrings::LINK_SECRET());
    unsigned char combined[32];
    EVP_MD_CTX* mdctx1 = EVP_MD_CTX_new();
    EVP_DigestInit_ex(mdctx1, EVP_sha256(), nullptr);
    EVP_DigestUpdate(mdctx1, userKey.data(), userKey.size());
    EVP_DigestUpdate(mdctx1, embedded.data(), embedded.size());
    unsigned int outlen1 = 0;
    EVP_DigestFinal_ex(mdctx1, combined, &outlen1);
    EVP_MD_CTX_free(mdctx1);
    std::string key(reinterpret_cast<char*>(combined), 32);
    
    // Inicializar contexto de encriptación
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw std::runtime_error("Failed to create encryption context");
    }
    
    // Generar IV aleatorio
    unsigned char iv[AES_BLOCK_SIZE];
    if (RAND_bytes(iv, AES_BLOCK_SIZE) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to generate IV");
    }
    
    // Inicializar encriptación AES-256-CBC
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_cbc(), NULL, 
                          reinterpret_cast<const unsigned char*>(key.c_str()), iv) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to initialize encryption");
    }
    
    // Preparar buffer de salida
    int len;
    int ciphertext_len;
    std::string ciphertext(plaintext.length() + AES_BLOCK_SIZE, 0);
    
    // Encriptar datos
    if (EVP_EncryptUpdate(ctx, reinterpret_cast<unsigned char*>(&ciphertext[0]), &len,
                         reinterpret_cast<const unsigned char*>(plaintext.c_str()), plaintext.length()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Encryption update failed");
    }
    ciphertext_len = len;
    
    // Finalizar encriptación
    if (EVP_EncryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&ciphertext[0]) + len, &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Encryption finalization failed");
    }
    ciphertext_len += len;
    
    // Limpiar contexto
    EVP_CIPHER_CTX_free(ctx);
    
    // Redimensionar resultado
    ciphertext.resize(ciphertext_len);
    
    // Combinar: salt (16) + iv (16) + ciphertext
    std::string result;
    result.reserve(16 + 16 + ciphertext.length());
    result += salt;
    result += std::string(reinterpret_cast<char*>(iv), AES_BLOCK_SIZE);
    result += ciphertext;
    
    return result;
}

std::string MainWindow::aesDecrypt(const std::string& ciphertext, const std::string& password) {
    if (ciphertext.length() < 32) { // salt (16) + iv (16)
        throw std::runtime_error("Invalid ciphertext length");
    }
    
    // Extraer salt, IV y datos encriptados
    std::string salt = ciphertext.substr(0, 16);
    std::string iv = ciphertext.substr(16, 16);
    std::string encrypted_data = ciphertext.substr(32);
    
    // Derivar clave y mezclar con secreta embebida
    std::string userKey = deriveKey(password, salt);
    std::string embedded(ObfuscatedStrings::LINK_SECRET());
    unsigned char combined[32];
    EVP_MD_CTX* mdctx2 = EVP_MD_CTX_new();
    EVP_DigestInit_ex(mdctx2, EVP_sha256(), nullptr);
    EVP_DigestUpdate(mdctx2, userKey.data(), userKey.size());
    EVP_DigestUpdate(mdctx2, embedded.data(), embedded.size());
    unsigned int outlen2 = 0;
    EVP_DigestFinal_ex(mdctx2, combined, &outlen2);
    EVP_MD_CTX_free(mdctx2);
    std::string key(reinterpret_cast<char*>(combined), 32);
    
    // Inicializar contexto de desencriptación
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw std::runtime_error("Failed to create decryption context");
    }
    
    // Inicializar desencriptación AES-256-CBC
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), NULL,
                          reinterpret_cast<const unsigned char*>(key.c_str()),
                          reinterpret_cast<const unsigned char*>(iv.c_str())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to initialize decryption");
    }
    
    // Preparar buffer de salida
    int len;
    int plaintext_len;
    std::string plaintext(encrypted_data.length() + AES_BLOCK_SIZE, 0);
    
    // Desencriptar datos
    if (EVP_DecryptUpdate(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]), &len,
                         reinterpret_cast<const unsigned char*>(encrypted_data.c_str()), encrypted_data.length()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Decryption update failed");
    }
    plaintext_len = len;
    
    // Finalizar desencriptación
    if (EVP_DecryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]) + len, &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Decryption finalization failed - wrong password?");
    }
    plaintext_len += len;
    
    // Limpiar contexto
    EVP_CIPHER_CTX_free(ctx);
    
    // Redimensionar resultado
    plaintext.resize(plaintext_len);
    
    return plaintext;
}

std::string simpleEncrypt(const std::string& data, const std::string& key) {
    std::string result;
    for (size_t i = 0; i < data.length(); ++i) {
        char encrypted = data[i] ^ key[i % key.length()];
        result += encrypted;
    }
    return result;
}

std::string simpleDecrypt(const std::string& encryptedData, const std::string& key) {
    return simpleEncrypt(encryptedData, key); // XOR is symmetric
}

std::string base64Encode(const std::string& data) {
    const std::string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    int val = 0, valb = -6;
    for (unsigned char c : data) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) {
            result.push_back(chars[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) result.push_back(chars[((val << 8) >> (valb + 8)) & 0x3F]);
    while (result.size() % 4) result.push_back('=');
    return result;
}

std::string base64Decode(const std::string& data) {
    const std::string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    std::vector<int> T(256, -1);
    for (int i = 0; i < 64; i++) T[chars[i]] = i;
    
    int val = 0, valb = -8;
    for (unsigned char c : data) {
        if (T[c] == -1) break;
        val = (val << 6) + T[c];
        valb += 6;
        if (valb >= 0) {
            result.push_back(char((val >> valb) & 0xFF));
            valb -= 8;
        }
    }
    return result;
}

bool MainWindow::downloadDirectFile(const FileInfo& fileInfo, const std::string& saveDir, const std::string& decryptPassword) {
    try {
        std::string fileName = fileInfo.fileName;
        std::string filePath = saveDir + "/" + fileName;
        
        LOG_INFO("Starting direct file download: " + fileName);
        
        // Use TelegramHandler to download the file
        bool success = m_telegramHandler->downloadFile(fileInfo.telegramFileId, filePath);
        
        if (success) {
            LOG_INFO("Direct file download completed: " + fileName);
            
            // Desencriptar si se proporcionó contraseña
            if (!decryptPassword.empty()) {
                LOG_INFO("Decrypting direct file...");
                
                std::string tempEncryptedPath = filePath + ".tmp";
                
                // Renombrar archivo descargado a temporal
                try {
                    std::filesystem::rename(filePath, tempEncryptedPath);
                } catch (const std::exception& e) {
                    LOG_ERROR("Failed to rename file for decryption: " + std::string(e.what()));
                    return false;
                }
                
                // Desencriptar
                if (!decryptFile(tempEncryptedPath, filePath, decryptPassword)) {
                    LOG_ERROR("Failed to decrypt direct file");
                    
                    // Restaurar archivo encriptado
                    try {
                        std::filesystem::rename(tempEncryptedPath, filePath);
                    } catch (...) {}
                    
                    return false;
                }
                
                // Eliminar archivo temporal encriptado
                try {
                    std::filesystem::remove(tempEncryptedPath);
                } catch (...) {}
                
                LOG_INFO("Direct file decrypted successfully");
            }
            
            return true;
        } else {
            LOG_ERROR("Direct file download failed: " + fileName);
            return false;
        }
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during direct file download: " + std::string(e.what()));
        return false;
    }
}

bool MainWindow::downloadChunkedFile(const std::string& fileId, const std::vector<ChunkInfo>& chunks, const std::string& saveDir, const std::string& decryptPassword) {
    try {
        if (chunks.empty()) {
            LOG_ERROR("No chunks found for file: " + fileId);
            return false;
        }
        
        // Get original filename from first chunk or database
        std::string fileName = "chunked_file_" + fileId.substr(0, 8);
        std::string filePath = saveDir + "/" + fileName;
        
        LOG_INFO("Starting chunked file download: " + fileName + " (" + std::to_string(chunks.size()) + " chunks)");
        
        // Simple sequential download for now
        std::ofstream outputFile(filePath, std::ios::binary);
        if (!outputFile) {
            LOG_ERROR("Cannot create output file: " + filePath);
            return false;
        }
        
        int completed = 0;
        for (const auto& chunk : chunks) {
            std::string tempPath = "temp_chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
            
            // Download chunk
            bool success = m_telegramHandler->downloadFile(chunk.telegramFileId, tempPath);
            if (success) {
                // Read and append chunk data
                std::ifstream chunkFile(tempPath, std::ios::binary);
                if (chunkFile) {
                    outputFile << chunkFile.rdbuf();
                    chunkFile.close();
                    std::remove(tempPath.c_str());
                    completed++;
                    
                    // Update progress
                    double percent = (double)completed / chunks.size() * 100.0;
                    wxTheApp->CallAfter([this, percent]() {
                        m_uploadProgress->SetValue((int)percent);
                        m_uploadStatusLabel->SetLabel(
                            wxString::Format("Downloading: %d%%", (int)percent)
                        );
                        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                    });
                }
            } else {
                LOG_ERROR("Failed to download chunk " + std::to_string(chunk.chunkNumber));
            }
        }
        
        outputFile.close();
        
        LOG_INFO("Chunked file download completed: " + fileName);
        
        // Desencriptar si se proporcionó contraseña
        if (!decryptPassword.empty()) {
            LOG_INFO("Decrypting chunked file...");
            
            std::string tempEncryptedPath = filePath + ".tmp";
            
            // Renombrar archivo descargado a temporal
            try {
                std::filesystem::rename(filePath, tempEncryptedPath);
            } catch (const std::exception& e) {
                LOG_ERROR("Failed to rename file for decryption: " + std::string(e.what()));
                return false;
            }
            
            // Desencriptar
            if (!decryptFile(tempEncryptedPath, filePath, decryptPassword)) {
                LOG_ERROR("Failed to decrypt chunked file");
                
                // Restaurar archivo encriptado
                try {
                    std::filesystem::rename(tempEncryptedPath, filePath);
                } catch (...) {}
                
                return false;
            }
            
            // Eliminar archivo temporal encriptado
            try {
                std::filesystem::remove(tempEncryptedPath);
            } catch (...) {}
            
            LOG_INFO("Chunked file decrypted successfully");
        }
        
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during chunked file download: " + std::string(e.what()));
        return false;
    }
}

void MainWindow::OnShare(wxCommandEvent& event) {
    // Verificar si hay archivos seleccionados
    if (m_selectedItems.empty()) {
        wxMessageBox("Please select one or more files to share.", "No File Selected", wxOK | wxICON_WARNING);
        return;
    }
    
    // Get user password
    wxString password = wxGetPasswordFromUser(
        "Enter a password to encrypt the share link:",
        m_selectedItems.size() > 1 ? "Batch Share Files" : "Share File",
        "",
        this
    );
    
    if (password.IsEmpty()) {
        return; // User cancelled
    }
    
    // Determinar nombre por defecto del archivo .link
    wxString defaultFileName;
    if (m_selectedItems.size() > 1) {
        defaultFileName = "batch_share.link";
    } else {
        // Obtener nombre del archivo individual
        long itemIndex = *m_selectedItems.begin();
        auto it = m_itemToFileId.find(itemIndex);
        if (it != m_itemToFileId.end()) {
            FileInfo fileInfo = m_database->getFileInfo(it->second);
            if (!fileInfo.fileName.empty()) {
                // Remover extensión original y agregar .link
                wxString originalName = wxString::FromUTF8(fileInfo.fileName);
                size_t lastDot = originalName.find_last_of(".");
                if (lastDot != wxString::npos) {
                    defaultFileName = originalName.substr(0, lastDot) + ".link";
                } else {
                    defaultFileName = originalName + ".link";
                }
            } else {
                defaultFileName = "share.link";
            }
        } else {
            defaultFileName = "share.link";
        }
    }
    
    // Seleccionar ubicación para guardar archivo .link
    wxFileDialog saveDialog(this, 
        "Save Universal Link File",
        "",
        defaultFileName,
        "Link files (*.link)|*.link",
        wxFD_SAVE | wxFD_OVERWRITE_PROMPT);
    
    if (saveDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    std::string linkFilePath = std::string(saveDialog.GetPath().mb_str());
    std::string userPassword = std::string(password.mb_str());
    
    try {
        UniversalLinkGenerator linkGenerator(m_database.get());
        
        if (m_selectedItems.size() > 1) {
            // Batch share
            LOG_INFO("Generating batch link file for " + std::to_string(m_selectedItems.size()) + " files");
            
            std::vector<std::string> fileIds;
            for (long itemIndex : m_selectedItems) {
                auto it = m_itemToFileId.find(itemIndex);
                if (it != m_itemToFileId.end()) {
                    fileIds.push_back(it->second);
                }
            }
            
            if (!linkGenerator.generateBatchLinkFile(fileIds, userPassword, linkFilePath)) {
                wxMessageBox("Failed to generate batch link file.", "Error", wxOK | wxICON_ERROR);
                return;
            }
            
        } else {
            // Single file share
            long itemIndex = *m_selectedItems.begin();
            auto it = m_itemToFileId.find(itemIndex);
            if (it == m_itemToFileId.end()) {
                wxMessageBox("Error: File ID not found.", "Error", wxOK | wxICON_ERROR);
                return;
            }
            
            std::string fileId = it->second;
            LOG_INFO("Generating link file for: " + fileId);
            
            if (!linkGenerator.generateLinkFile(fileId, userPassword, linkFilePath)) {
                wxMessageBox("Failed to generate link file.", "Error", wxOK | wxICON_ERROR);
                return;
            }
        }
        
        wxMessageBox(
            "Universal link file created successfully!\n\n"
            "Location: " + linkFilePath + "\n\n"
            "Share this .link file with anyone who has the application.\n"
            "They can download the file(s) directly without needing your database.",
            "Link File Created",
            wxOK | wxICON_INFORMATION
        );
        
        LOG_INFO("Universal link file created: " + linkFilePath);
        
    } catch (const std::exception& e) {
        wxMessageBox("Failed to create link file: " + std::string(e.what()), 
                    "Error", wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnDownloadFromLink(wxCommandEvent& event) {
    // Seleccionar archivo .link
    wxFileDialog openDialog(this,
        "Select Universal Link File",
        "",
        "",
        "Link files (*.link)|*.link",
        wxFD_OPEN | wxFD_FILE_MUST_EXIST);
    
    if (openDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    std::string linkFilePath = std::string(openDialog.GetPath().mb_str());
    
    // Get password to decrypt link
    wxString password = wxGetPasswordFromUser(
        "Enter the password to decrypt the link:",
        "Decrypt Link",
        "",
        this
    );
    
    if (password.IsEmpty()) {
        return; // User cancelled
    }
    
    std::string linkPassword = std::string(password.mb_str());
    
    try {
        // Obtener información del link
        UniversalLinkDownloader linkDownloader(m_telegramHandler.get(), m_database.get(), m_telegramNotifier.get());
        std::vector<FileInfo> filesInfo = linkDownloader.getLinkFileInfo(linkFilePath, linkPassword);
        
        if (filesInfo.empty()) {
            wxMessageBox(
                "Failed to Read Link File\n\n"
                "Possible causes:\n"
                "• Wrong password\n"
                "• Corrupted or invalid .link file\n"
                "• File format not recognized",
                "Error", 
                wxOK | wxICON_ERROR
            );
            return;
        }
        
        // Check if any file is encrypted
        bool hasEncryptedFiles = false;
        for (const auto& fileInfo : filesInfo) {
            if (fileInfo.isEncrypted) {
                hasEncryptedFiles = true;
                break;
            }
        }
        
        // If files are encrypted, ask for decryption password
        std::string filePassword;
        if (hasEncryptedFiles) {
            wxString filePwd = wxGetPasswordFromUser(
                "One or more files are encrypted. Enter the file decryption password:",
                "File Decryption Password",
                "",
                this
            );
            
            if (filePwd.IsEmpty()) {
                return; // User cancelled
            }
            
            filePassword = std::string(filePwd.mb_str());
        }
        
        // Start actual download
        wxString saveDir = wxDirSelector("Choose download directory:", "", wxDD_DEFAULT_STYLE, wxDefaultPosition, this);
        if (saveDir.IsEmpty()) {
            return; // User cancelled
        }
        
        std::string destinationDir = std::string(saveDir.mb_str());
        
        // Show progress
        m_uploadProgress->Show();
        m_uploadProgress->SetValue(0);
        m_uploadStatusLabel->SetLabel("Downloading from link...");
        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
        
        // Establecer controles de operación
        UpdateOperationControls(true, OperationType::DOWNLOAD_LINK);
        
        // Start download in background thread
        std::thread downloadThread([this, linkFilePath, linkPassword, destinationDir, filePassword, filesInfo]() {
            try {
                UniversalLinkDownloader linkDownloader(m_telegramHandler.get(), m_database.get(), m_telegramNotifier.get());
                
                // Progress callback
                UniversalLinkProgressCallback progressCallback = [this](int current, int total, const std::string& fileName, double percent) {
                    wxTheApp->CallAfter([this, current, total, fileName, percent]() {
                        m_uploadProgress->SetValue((int)percent);
                        
                        wxString statusText;
                        
                        // Valores negativos indican reconstrucción
                        if (current < 0 && total < 0) {
                            int reconstructed = -current;
                            int totalToReconstruct = -total;
                            statusText = wxString::Format("Reconstructing: %s - Chunks %d/%d (%d%%)", 
                                wxString::FromUTF8(fileName), reconstructed, totalToReconstruct, (int)percent);
                            m_uploadStatusLabel->SetForegroundColour(wxColour(255, 200, 0));
                        }
                        // Mostrar chunks si total > 1, de lo contrario es descarga directa
                        else if (total > 1) {
                            statusText = wxString::Format("Downloading: %s - Chunks %d/%d (%d%%)", 
                                wxString::FromUTF8(fileName), current, total, (int)percent);
                            m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                        } else {
                            statusText = wxString::Format("Downloading: %s (%d%%)", 
                                wxString::FromUTF8(fileName), (int)percent);
                            m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                        }
                        
                        m_uploadStatusLabel->SetLabel(statusText);
                    });
                };
                
                bool success = linkDownloader.downloadFromLinkFile(
                    linkFilePath,
                    linkPassword,
                    destinationDir,
                    filePassword,
                    progressCallback
                );
                
                // Update UI on completion
                wxTheApp->CallAfter([this, success, filesInfo]() {
                    if (success) {
                        m_uploadProgress->SetValue(100);
                        m_uploadStatusLabel->SetLabel("Download completed");
                        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                        
                        wxString filesList;
                        for (size_t i = 0; i < filesInfo.size() && i < 5; i++) {
                            filesList += "- " + wxString::FromUTF8(filesInfo[i].fileName) + "\n";
                        }
                        if (filesInfo.size() > 5) {
                            filesList += wxString::Format("... and %d more files\n", (int)filesInfo.size() - 5);
                        }
                        
                        wxString msg = wxString::Format(
                            "Download Successful\n\n"
                            "Downloaded %d file(s):\n%s\n"
                            "Check logs for details.",
                            (int)filesInfo.size(), filesList
                        );
                        
                        wxMessageBox(msg, "Download Completed", wxOK | wxICON_INFORMATION);
                    } else {
                        m_uploadStatusLabel->SetLabel("Download failed");
                        m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                        
                        wxMessageBox(
                            "Download Failed\n\n"
                            "Check logs for details.",
                            "Download Failed", wxOK | wxICON_ERROR
                        );
                    }
                    
                    wxSleep(2);
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                    UpdateOperationControls(false);
                });
                
            } catch (const std::exception& e) {
                LOG_CRITICAL("Exception in download thread: " + std::string(e.what()));
                
                wxTheApp->CallAfter([this, e]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    UpdateOperationControls(false);
                    
                    wxString msg = wxString::Format(
                        "Download crashed with exception:\n\n%s\n\n"
                        "Check logs for details.",
                        e.what()
                    );
                    
                    wxMessageBox(msg, "Download Error", wxOK | wxICON_ERROR);
                });
            }
        });
        
        downloadThread.detach();
        
        LOG_INFO("Universal link download started for " + std::to_string(filesInfo.size()) + " file(s)");
        
    } catch (const std::exception& e) {
        std::string errorMsg = std::string(e.what());
        
        m_uploadProgress->Hide();
        m_uploadStatusLabel->SetLabel("Ready");
        
        if (errorMsg.find("Wrong password") != std::string::npos || 
            errorMsg.find("finalize decryption") != std::string::npos) {
            wxMessageBox(
                "Wrong Password\n\n"
                "The password you entered is incorrect.\n"
                "Please verify the password and try again.",
                "Decryption Failed", 
                wxOK | wxICON_ERROR
            );
        } else if (errorMsg.find("Invalid link file") != std::string::npos) {
            wxMessageBox(
                "Invalid Link File\n\n"
                "The selected file is not a valid .link file\n"
                "or it may be corrupted.",
                "Invalid File", 
                wxOK | wxICON_ERROR
            );
        } else {
            wxMessageBox(
                "Error Processing Link File\n\n" + errorMsg + "\n\n"
                "Check logs for details.",
                "Error", 
                wxOK | wxICON_ERROR
            );
        }
    }
}

void MainWindow::OnSearch(wxCommandEvent& event) {
    wxString searchText = m_searchTextCtrl->GetValue();
    m_currentSearch = std::string(searchText.mb_str());
    
    LOG_INFO("Manual search for: " + m_currentSearch);
    LoadFiles();
}

void MainWindow::OnSearchTextChanged(wxCommandEvent& event) {
    wxString searchText = m_searchTextCtrl->GetValue();
    m_currentSearch = std::string(searchText.mb_str());
    
    // If search is empty, clear the filter (same as pressing Clear)
    if (m_currentSearch.empty()) {
        LOG_DEBUG("Search field cleared - showing all files");
    } else {
        LOG_DEBUG("Auto-search for: " + m_currentSearch);
    }
    
    LoadFiles();
}

void MainWindow::OnClearSearch(wxCommandEvent& event) {
    m_searchTextCtrl->SetValue("");
    m_currentSearch = "";
    
    LOG_INFO("Search cleared manually");
    LoadFiles();
}

void MainWindow::OnSortByName(wxCommandEvent& event) {
    m_currentSortBy = "name";
    LOG_INFO("Sorting by name");
    LoadFiles();
}

void MainWindow::OnSortBySize(wxCommandEvent& event) {
    m_currentSortBy = "size";
    LOG_INFO("Sorting by size");
    LoadFiles();
}

void MainWindow::OnSortByDate(wxCommandEvent& event) {
    m_currentSortBy = "date";
    LOG_INFO("Sorting by date");
    LoadFiles();
}

void MainWindow::OnSortByType(wxCommandEvent& event) {
    m_currentSortBy = "type";
    LOG_INFO("Sorting by type");
    LoadFiles();
}

void MainWindow::OnSortAscending(wxCommandEvent& event) {
    m_sortAscending = true;
    LOG_INFO("Sort order: ascending");
    LoadFiles();
}

void MainWindow::OnSortDescending(wxCommandEvent& event) {
    m_sortAscending = false;
    LOG_INFO("Sort order: descending");
    LoadFiles();
}

void MainWindow::OnConfig(wxCommandEvent& event) {
    LOG_INFO("Opening configuration dialog");
    
    // Create configuration dialog
    wxDialog configDialog(this, wxID_ANY, "Configuration (Encrypted)", wxDefaultPosition, wxSize(600, 500));
    wxBoxSizer* mainSizer = new wxBoxSizer(wxVERTICAL);
    
    // Load current configuration from EnvManager
    EnvManager& envMgr = EnvManager::instance();
    envMgr.load();
    
    std::string botToken = envMgr.get("BOT_TOKEN");
    std::string apiId = envMgr.get("API_ID");
    std::string apiHash = envMgr.get("API_HASH");
    std::string channelId = envMgr.get("CHANNEL_ID");
    std::string additionalBotTokens = envMgr.get("ADDITIONAL_BOT_TOKENS");
    
    // Create form fields
    wxStaticBoxSizer* credentialsBox = new wxStaticBoxSizer(wxVERTICAL, &configDialog, "Telegram API Credentials");
    
    // API ID field
    wxBoxSizer* apiIdSizer = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* apiIdLabel = new wxStaticText(&configDialog, wxID_ANY, "API ID:");
    apiIdLabel->SetMinSize(wxSize(100, -1));
    wxTextCtrl* apiIdCtrl = new wxTextCtrl(&configDialog, wxID_ANY, wxString(apiId), wxDefaultPosition, wxSize(200, -1));
    wxButton* getApiIdLink = nullptr;
    
    // Only show "Get API ID" link if API ID or API Hash is empty
    if (apiId.empty() || apiHash.empty()) {
        getApiIdLink = new wxButton(&configDialog, wxID_ANY, "Get API ID", wxDefaultPosition, wxSize(100, -1));
        getApiIdLink->SetForegroundColour(*wxBLUE);
        getApiIdLink->Bind(wxEVT_BUTTON, [](wxCommandEvent&) {
            wxLaunchDefaultBrowser("https://my.telegram.org/apps");
        });
    }
    
    apiIdSizer->Add(apiIdLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    apiIdSizer->Add(apiIdCtrl, 1, wxALL, 5);
    if (getApiIdLink) {
        apiIdSizer->Add(getApiIdLink, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    }
    
    // API Hash field
    wxBoxSizer* apiHashSizer = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* apiHashLabel = new wxStaticText(&configDialog, wxID_ANY, "API Hash:");
    apiHashLabel->SetMinSize(wxSize(100, -1));
    wxTextCtrl* apiHashCtrl = new wxTextCtrl(&configDialog, wxID_ANY, wxString(apiHash), wxDefaultPosition, wxSize(200, -1));
    apiHashSizer->Add(apiHashLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    apiHashSizer->Add(apiHashCtrl, 1, wxALL, 5);
    
    credentialsBox->Add(apiIdSizer, 0, wxEXPAND);
    credentialsBox->Add(apiHashSizer, 0, wxEXPAND);
    
    // Bot Token field
    wxStaticBoxSizer* botBox = new wxStaticBoxSizer(wxVERTICAL, &configDialog, "Bot Configuration");
    
    wxBoxSizer* botTokenSizer = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* botTokenLabel = new wxStaticText(&configDialog, wxID_ANY, "Bot Token:");
    botTokenLabel->SetMinSize(wxSize(100, -1));
    wxTextCtrl* botTokenCtrl = new wxTextCtrl(&configDialog, wxID_ANY, wxString(botToken), wxDefaultPosition, wxSize(300, -1));
    wxButton* botFatherLink = new wxButton(&configDialog, wxID_ANY, "BotFather", wxDefaultPosition, wxSize(80, -1));
    botFatherLink->SetForegroundColour(*wxBLUE);
    botFatherLink->Bind(wxEVT_BUTTON, [](wxCommandEvent&) {
        wxLaunchDefaultBrowser("https://t.me/botfather");
    });
    
    botTokenSizer->Add(botTokenLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    botTokenSizer->Add(botTokenCtrl, 1, wxALL, 5);
    botTokenSizer->Add(botFatherLink, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    
    // Additional Bot Tokens field
    wxBoxSizer* additionalTokensSizer = new wxBoxSizer(wxVERTICAL);
    wxBoxSizer* additionalTokensInputSizer = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* additionalTokensLabel = new wxStaticText(&configDialog, wxID_ANY, "Additional Bots:");
    additionalTokensLabel->SetMinSize(wxSize(100, -1));
    wxTextCtrl* additionalTokensCtrl = new wxTextCtrl(&configDialog, wxID_ANY, wxString(additionalBotTokens), wxDefaultPosition, wxSize(300, -1));
    additionalTokensCtrl->SetHint("token1,token2,token3");
    
    additionalTokensInputSizer->Add(additionalTokensLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    additionalTokensInputSizer->Add(additionalTokensCtrl, 1, wxALL, 5);
    
    // Warning message
    wxStaticText* additionalTokensWarning = new wxStaticText(&configDialog, wxID_ANY, 
        "Separate tokens with commas (no spaces): token1,token2,token3");
    additionalTokensWarning->SetFont(wxFont(8, wxFONTFAMILY_DEFAULT, wxFONTSTYLE_ITALIC, wxFONTWEIGHT_NORMAL));
    additionalTokensWarning->SetForegroundColour(wxColour(255, 60, 60));
    
    additionalTokensSizer->Add(additionalTokensInputSizer, 0, wxEXPAND);
    additionalTokensSizer->Add(additionalTokensWarning, 0, wxLEFT, 110);
    
    // Channel ID field
    wxBoxSizer* channelIdSizer = new wxBoxSizer(wxHORIZONTAL);
    wxStaticText* channelIdLabel = new wxStaticText(&configDialog, wxID_ANY, "Channel ID:");
    channelIdLabel->SetMinSize(wxSize(100, -1));
    wxTextCtrl* channelIdCtrl = new wxTextCtrl(&configDialog, wxID_ANY, wxString(channelId), wxDefaultPosition, wxSize(200, -1));
    wxButton* channelIdLink = new wxButton(&configDialog, wxID_ANY, "Get Channel ID", wxDefaultPosition, wxSize(120, -1));
    channelIdLink->SetForegroundColour(*wxBLUE);
    channelIdLink->Bind(wxEVT_BUTTON, [](wxCommandEvent&) {
        wxLaunchDefaultBrowser("https://t.me/userinfobot");
    });
    
    channelIdSizer->Add(channelIdLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    channelIdSizer->Add(channelIdCtrl, 1, wxALL, 5);
    channelIdSizer->Add(channelIdLink, 0, wxALL | wxALIGN_CENTER_VERTICAL, 5);
    
    botBox->Add(botTokenSizer, 0, wxEXPAND);
    botBox->Add(additionalTokensSizer, 0, wxEXPAND);
    botBox->Add(channelIdSizer, 0, wxEXPAND);
    
    // Buttons
    wxBoxSizer* buttonSizer = new wxBoxSizer(wxHORIZONTAL);
    wxButton* saveButton = new wxButton(&configDialog, wxID_OK, "Save");
    wxButton* cancelButton = new wxButton(&configDialog, wxID_CANCEL, "Cancel");
    
    buttonSizer->Add(saveButton, 0, wxALL, 5);
    buttonSizer->Add(cancelButton, 0, wxALL, 5);
    
    // Layout
    mainSizer->Add(credentialsBox, 0, wxEXPAND | wxALL, 10);
    mainSizer->Add(botBox, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 10);
    mainSizer->Add(buttonSizer, 0, wxALIGN_CENTER | wxALL, 10);
    
    configDialog.SetSizer(mainSizer);
    
    // Handle save button
    saveButton->Bind(wxEVT_BUTTON, [&configDialog, apiIdCtrl, apiHashCtrl, botTokenCtrl, additionalTokensCtrl, channelIdCtrl](wxCommandEvent&) {
        std::string newApiId = std::string(apiIdCtrl->GetValue().mb_str());
        std::string newApiHash = std::string(apiHashCtrl->GetValue().mb_str());
        std::string newBotToken = std::string(botTokenCtrl->GetValue().mb_str());
        std::string newAdditionalTokens = std::string(additionalTokensCtrl->GetValue().mb_str());
        std::string newChannelId = std::string(channelIdCtrl->GetValue().mb_str());
        
        // Validate required inputs
        if (newApiId.empty() || newApiHash.empty() || newBotToken.empty() || newChannelId.empty()) {
            wxMessageBox("Please fill in all required fields (API ID, API Hash, Bot Token, Channel ID).", "Validation Error", wxOK | wxICON_WARNING);
            return;
        }
        
        // Save to encrypted configuration using EnvManager
        EnvManager& envMgr = EnvManager::instance();
        
        envMgr.set("BOT_TOKEN", newBotToken);
        envMgr.set("API_ID", newApiId);
        envMgr.set("API_HASH", newApiHash);
        envMgr.set("CHANNEL_ID", newChannelId);
        
        if (!newAdditionalTokens.empty()) {
            envMgr.set("ADDITIONAL_BOT_TOKENS", newAdditionalTokens);
        } else {
            envMgr.remove("ADDITIONAL_BOT_TOKENS");
        }
        
        if (envMgr.save()) {
            wxMessageBox("Configuration saved successfully and encrypted!\n\nPlease restart the application to load the new configuration.", 
                        "Success", wxOK | wxICON_INFORMATION);
            configDialog.EndModal(wxID_OK);
        } else {
            wxMessageBox("Error saving encrypted configuration: " + envMgr.lastError(), "Error", wxOK | wxICON_ERROR);
        }
    });
    
    configDialog.ShowModal();
}

void MainWindow::ShowConfigDialog() {
    LOG_INFO("Showing configuration dialog for first-time setup");
    wxCommandEvent evt;
    OnConfig(evt);
}

void MainWindow::SetAppIcon() {
    // Create a simple icon programmatically
    wxBitmap bitmap(32, 32);
    wxMemoryDC dc(bitmap);
    
    // Clear background
    dc.SetBackground(*wxWHITE_BRUSH);
    dc.Clear();
    
    // Draw cloud shape
    dc.SetBrush(wxBrush(wxColour(100, 150, 255)));
    dc.SetPen(wxPen(wxColour(80, 130, 235), 1));
    
    // Draw cloud (simple rounded rectangle)
    dc.DrawRoundedRectangle(2, 8, 28, 16, 4);
    dc.DrawRoundedRectangle(6, 4, 20, 20, 6);
    
    // Draw paper airplane
    dc.SetBrush(wxBrush(*wxWHITE));
    dc.SetPen(wxPen(*wxWHITE, 2));
    dc.DrawLine(16, 14, 24, 10);
    dc.DrawLine(16, 14, 20, 18);
    dc.DrawLine(20, 18, 24, 10);
    
    dc.SelectObject(wxNullBitmap);
    
    // Convert to icon
    wxIcon icon;
    icon.CopyFromBitmap(bitmap);
    if (icon.IsOk()) {
        SetIcon(icon);
    }
}

// ===== BATCH OPERATIONS IMPLEMENTATION =====

void MainWindow::OnListItemActivated(wxListEvent& event) {
    // Double-click en un item - toggle checkbox
    long index = event.GetIndex();
    
    if (m_selectedItems.count(index)) {
        // Deseleccionar
        m_selectedItems.erase(index);
        m_filesListCtrl->SetItem(index, 0, "[ ]");
    } else {
        // Seleccionar
        m_selectedItems.insert(index);
        m_filesListCtrl->SetItem(index, 0, "[X]");
    }
    
    LOG_DEBUG("Item " + std::to_string(index) + " toggled. Selected items: " + std::to_string(m_selectedItems.size()));
}

void MainWindow::OnListItemClick(wxListEvent& event) {
    // Click simple en un item - actualizar selección visual
    long index = event.GetIndex();
    
    // Usar GetItemState para verificar si está seleccionado por wxWidgets
    bool isSelected = m_filesListCtrl->GetItemState(index, wxLIST_STATE_SELECTED) != 0;
    
    if (isSelected) {
        if (!m_selectedItems.count(index)) {
            m_selectedItems.insert(index);
            m_filesListCtrl->SetItem(index, 0, "[X]");
        }
    } else {
        if (m_selectedItems.count(index)) {
            m_selectedItems.erase(index);
            m_filesListCtrl->SetItem(index, 0, "[ ]");
        }
    }
}

// ===== PROTECCIÓN DE ENLACE DE CONTACTO =====

void MainWindow::validateContactLink() {
    if (!m_contactLink) {
        LOG_ERROR("Contact link not initialized - terminating application");
        terminateApplication();
        return;
    }
    
    // Verificar que el enlace no ha sido modificado
    std::string currentUrl = std::string(m_contactLink->GetURL().mb_str());
    std::string expectedUrl = "https://t.me/Brainagi";
    
    if (currentUrl != expectedUrl) {
        LOG_ERROR("Contact link has been modified - terminating application");
        LOG_ERROR("Expected: " + expectedUrl);
        LOG_ERROR("Found: " + currentUrl);
        terminateApplication();
        return;
    }
    
    // Validación distribuida en múltiples módulos
    bool isValid = true;
    
    isValid &= verifySecurity(); // Validar protocol
    isValid &= checkProtocol();  // Validar domain
    isValid &= checkSystem();    // Validar username
    
    if (!isValid) {
        LOG_ERROR("Distributed validation failed - terminating application");
        terminateApplication();
        return;
    }
    
    // Verificar checksum del enlace
    std::string calculatedChecksum = calculateContactChecksum();
    std::string expectedChecksum = "otydinsxchmrwb"; // Checksum calculado y distribuido
    
    if (calculatedChecksum != expectedChecksum) {
        LOG_ERROR("Contact link checksum validation failed - terminating application");
        LOG_ERROR("Expected checksum: " + expectedChecksum);
        LOG_ERROR("Calculated checksum: " + calculatedChecksum);
        terminateApplication();
        return;
    }
    
    LOG_INFO("Contact link validation successful");
}

std::string MainWindow::calculateContactChecksum() {
    // Reconstruir checksum desde constantes distribuidas en diferentes módulos
    std::string checksum = "";
    checksum += VALIDATION_TOKEN_A;    // "ot" - desde config.cpp
    checksum += VALIDATION_TOKEN_B;    // "yd" - desde config.cpp
    checksum += INTEGRITY_MARKER_C;    // "in" - desde fileuploader.cpp
    checksum += INTEGRITY_MARKER_D;    // "sx" - desde fileuploader.cpp
    checksum += INTEGRITY_MARKER_E;    // "ch" - desde fileuploader.cpp
    checksum += SECURITY_FLAG_F;       // "mr" - desde filedownloader.cpp
    checksum += SECURITY_FLAG_G;       // "wb" - desde filedownloader.cpp
    
    return checksum;
}

void MainWindow::terminateApplication() {
    LOG_CRITICAL("Application terminated due to contact link protection violation");
    
    // Mostrar mensaje de error
    wxMessageBox(
        "Application integrity violation detected.\n\n"
        "The application has been terminated for security reasons.\n\n"
        "Please contact support if this error persists.",
        "Security Violation",
        wxOK | wxICON_ERROR
    );
    
    // Terminar aplicación inmediatamente
    std::exit(1);
}

// ============================================================================
// Operation Control Handlers
// ============================================================================

void MainWindow::OnPauseUpload(wxCommandEvent& event) {
    bool success = false;
    std::string operationId;
    
    if (m_currentOperationType == OperationType::UPLOAD && !m_currentUploadId.empty()) {
        operationId = m_currentUploadId;
        ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
        success = upload.pauseUpload(m_currentUploadId);
    } else if ((m_currentOperationType == OperationType::DOWNLOAD || m_currentOperationType == OperationType::DOWNLOAD_LINK) && !m_currentDownloadId.empty()) {
        operationId = m_currentDownloadId;
        ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
        success = downloader.pauseDownload(m_currentDownloadId);
    } else {
        wxMessageBox("No active operation to pause", "Info", 
                    wxOK | wxICON_INFORMATION);
        return;
    }
    
    if (success) {
        int currentValue = m_uploadProgress->GetValue();
        m_uploadStatusLabel->SetLabel(
            wxString::Format("Operation PAUSED (%d%% completed)", currentValue)
        );
        m_uploadStatusLabel->SetForegroundColour(wxColour(255, 165, 0));
        
        m_pauseButton->Enable(false);
        m_resumeButton->Enable(true);
        m_stopButton->Enable(false);
        m_cancelButton->Enable(true);
        
        LOG_INFO("Operation paused by user: " + operationId);
        
        wxMessageBox("Operation paused. Use 'Resume' to continue.", "Info", 
                    wxOK | wxICON_INFORMATION);
    } else {
        wxMessageBox("Error pausing operation", "Error", 
                    wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnStopUpload(wxCommandEvent& event) {
    bool hasOperation = false;
    std::string operationId;
    
    if (m_currentOperationType == OperationType::UPLOAD && !m_currentUploadId.empty()) {
        hasOperation = true;
        operationId = m_currentUploadId;
    } else if ((m_currentOperationType == OperationType::DOWNLOAD || m_currentOperationType == OperationType::DOWNLOAD_LINK) && !m_currentDownloadId.empty()) {
        hasOperation = true;
        operationId = m_currentDownloadId;
    }
    
    if (!hasOperation) {
        wxMessageBox("No active operation to stop", "Info", 
                    wxOK | wxICON_INFORMATION);
        return;
    }
    
    int response = wxMessageBox(
        "Stop this operation?\n\nProgress will be saved and you can resume later.",
        "Stop Operation",
        wxYES_NO | wxICON_QUESTION);
    
    if (response == wxYES) {
        bool success = false;
        
        if (m_currentOperationType == OperationType::UPLOAD) {
            ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
            success = upload.stopUpload(m_currentUploadId);
            m_currentUploadId.clear();
        } else {
            ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
            success = downloader.stopDownload(m_currentDownloadId);
            m_currentDownloadId.clear();
        }
        
        if (success) {
            int currentValue = m_uploadProgress->GetValue();
            m_uploadStatusLabel->SetLabel(
                wxString::Format("Operation STOPPED (%d%% completed - Progress saved)", currentValue)
            );
            m_uploadStatusLabel->SetForegroundColour(wxColour(255, 200, 0));
            
            m_pauseButton->Enable(false);
            m_resumeButton->Enable(true);
            m_stopButton->Enable(false);
            m_cancelButton->Enable(true);
            
            LOG_INFO("Operation stopped by user: " + operationId);
            
            wxMessageBox("Operation stopped. Progress has been saved.", "Info", 
                        wxOK | wxICON_INFORMATION);
        } else {
            wxMessageBox("Error al detener la carga", "Error", 
                        wxOK | wxICON_ERROR);
        }
    }
}

void MainWindow::OnCancelUpload(wxCommandEvent& event) {
    bool hasOperation = false;
    std::string operationId;
    
    if (m_currentOperationType == OperationType::UPLOAD && !m_currentUploadId.empty()) {
        hasOperation = true;
        operationId = m_currentUploadId;
    } else if ((m_currentOperationType == OperationType::DOWNLOAD || m_currentOperationType == OperationType::DOWNLOAD_LINK) && !m_currentDownloadId.empty()) {
        hasOperation = true;
        operationId = m_currentDownloadId;
    }
    
    if (!hasOperation) {
        OnShowIncompleteUploads(event);
        return;
    }
    
    int response = wxMessageBox(
        "CANCEL this operation?\n\nAll progress will be lost!",
        "Cancel Operation",
        wxYES_NO | wxICON_WARNING);
    
    if (response == wxYES) {
        bool success = false;
        
        if (m_currentOperationType == OperationType::UPLOAD) {
            ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
            success = upload.cancelUpload(m_currentUploadId);
        } else {
            ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
            success = downloader.cancelDownload(m_currentDownloadId);
        }
        
        if (success) {
            m_uploadStatusLabel->SetLabel("Operation CANCELED (All progress deleted)");
            m_uploadStatusLabel->SetForegroundColour(wxColour(255, 0, 0));
            
            m_uploadProgress->SetValue(0);
            UpdateOperationControls(false);
            m_currentUploadId.clear();
            m_currentDownloadId.clear();
            
            LOG_INFO("Operation canceled by user: " + operationId);
            
            wxMessageBox("Operation canceled. All progress has been deleted.", "Info", 
                        wxOK | wxICON_INFORMATION);
        } else {
            wxMessageBox("Error canceling operation", "Error", 
                        wxOK | wxICON_ERROR);
        }
    }
}

void MainWindow::OnResumeUpload(wxCommandEvent& event) {
    ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
    std::vector<ChunkedFileInfo> incompleteUploads = upload.getIncompleteUploads();
    
    if (incompleteUploads.empty()) {
        wxMessageBox("No hay cargas incompletas para reanudar", "Info", 
                    wxOK | wxICON_INFORMATION);
        return;
    }
    
    wxArrayString choices;
    for (const auto& info : incompleteUploads) {
        wxString item = wxString::Format("%s (%lld/%lld chunks = %.1f%%) - %s",
            info.originalFilename.c_str(),
            info.completedChunks,
            info.totalChunks,
            (info.completedChunks * 100.0 / info.totalChunks),
            info.status.c_str());
        choices.Add(item);
    }
    
    wxSingleChoiceDialog dialog(this,
        "Selecciona la carga a reanudar:",
        "Reanudar Carga",
        choices);
    
    if (dialog.ShowModal() == wxID_OK) {
        int selection = dialog.GetSelection();
        const auto& selectedUpload = incompleteUploads[selection];
        
        wxFileDialog fileDialog(this,
            "Selecciona el archivo original: " + wxString(selectedUpload.originalFilename),
            "", selectedUpload.originalFilename,
            "*.*",
            wxFD_OPEN | wxFD_FILE_MUST_EXIST);
        
        if (fileDialog.ShowModal() == wxID_OK) {
            m_currentUploadId = selectedUpload.fileId;
            m_uploadStatusLabel->SetLabel("Reanudando carga...");
            m_uploadStatusLabel->SetForegroundColour(wxColour(0, 255, 0));
            
            UpdateOperationControls(true, OperationType::UPLOAD);
            
            LOG_INFO("Resuming upload from UI: " + selectedUpload.fileId);
        }
    }
}

void MainWindow::OnShowIncompleteUploads(wxCommandEvent& event) {
    // Obtener operaciones incompletas de ambos tipos
    ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
    std::vector<ChunkedFileInfo> incompleteUploads = upload.getIncompleteUploads();
    
    ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
    std::vector<DownloadInfo> incompleteDownloads = downloader.getIncompleteDownloads();
    
    if (incompleteUploads.empty() && incompleteDownloads.empty()) {
        wxMessageBox("No pending operations found", "Info", 
                    wxOK | wxICON_INFORMATION);
        return;
    }
    
    wxDialog* dialog = new wxDialog(this, wxID_ANY, "Pending Operations", 
                                   wxDefaultPosition, wxSize(1000, 500));
    
    // Tema oscuro para el diálogo
    dialog->SetBackgroundColour(wxColour(30, 30, 30));
    
    wxBoxSizer* sizer = new wxBoxSizer(wxVERTICAL);
    
    // Título con estilo
    wxStaticText* title = new wxStaticText(dialog, wxID_ANY, "PENDING OPERATIONS");
    wxFont titleFont = title->GetFont();
    titleFont.SetPointSize(16);
    titleFont.SetWeight(wxFONTWEIGHT_BOLD);
    title->SetFont(titleFont);
    title->SetForegroundColour(wxColour(150, 150, 255));
    sizer->Add(title, 0, wxALL | wxALIGN_CENTER_HORIZONTAL, 15);
    
    // Instrucciones
    wxStaticText* instructions = new wxStaticText(dialog, wxID_ANY, 
        wxString::Format("Found %d upload(s) and %d download(s) - Select one and click Resume:",
                        (int)incompleteUploads.size(), (int)incompleteDownloads.size()));
    instructions->SetForegroundColour(wxColour(180, 180, 180));
    sizer->Add(instructions, 0, wxLEFT | wxRIGHT | wxBOTTOM, 15);
    
    wxListCtrl* list = new wxListCtrl(dialog, wxID_ANY, 
                                     wxDefaultPosition, wxDefaultSize,
                                     wxLC_REPORT | wxLC_SINGLE_SEL);
    
    // Tema oscuro para la lista
    list->SetBackgroundColour(wxColour(40, 40, 40));
    list->SetForegroundColour(wxColour(220, 220, 220));
    
    list->InsertColumn(0, "Type", wxLIST_FORMAT_CENTER, 100);
    list->InsertColumn(1, "File Name", wxLIST_FORMAT_LEFT, 280);
    list->InsertColumn(2, "Progress", wxLIST_FORMAT_CENTER, 170);
    list->InsertColumn(3, "Status", wxLIST_FORMAT_CENTER, 100);
    list->InsertColumn(4, "Size", wxLIST_FORMAT_RIGHT, 130);
    
    int itemIndex = 0;
    
    // Agregar uploads
    for (size_t i = 0; i < incompleteUploads.size(); i++) {
        const auto& info = incompleteUploads[i];
        
        long idx = list->InsertItem(itemIndex, "Upload");
        list->SetItemTextColour(idx, wxColour(100, 180, 255));
        
        list->SetItem(idx, 1, info.originalFilename);
        
        wxString progress = wxString::Format("%lld/%lld chunks (%.1f%%)", 
            info.completedChunks, 
            info.totalChunks,
            (info.completedChunks * 100.0 / info.totalChunks));
        list->SetItem(idx, 2, progress);
        
        list->SetItem(idx, 3, info.status);
        
        double sizeMB = info.totalSize / 1024.0 / 1024.0;
        wxString size;
        if (sizeMB > 1024) {
            size = wxString::Format("%.2f GB", sizeMB / 1024.0);
        } else {
            size = wxString::Format("%.2f MB", sizeMB);
        }
        list->SetItem(idx, 4, size);
        
        // Guardar índice en el vector de uploads como dato del item
        list->SetItemData(idx, i);
        itemIndex++;
    }
    
    // Agregar downloads
    for (size_t i = 0; i < incompleteDownloads.size(); i++) {
        const auto& info = incompleteDownloads[i];
        
        long idx = list->InsertItem(itemIndex, "Download");
        list->SetItemTextColour(idx, wxColour(100, 200, 100));
        
        list->SetItem(idx, 1, info.fileName);
        
        wxString progress = wxString::Format("%lld/%lld chunks (%.1f%%)", 
            info.completedChunks, 
            info.totalChunks,
            (info.completedChunks * 100.0 / info.totalChunks));
        list->SetItem(idx, 2, progress);
        
        list->SetItem(idx, 3, info.status);
        
        double sizeMB = info.totalSize / 1024.0 / 1024.0;
        wxString size;
        if (sizeMB > 1024) {
            size = wxString::Format("%.2f GB", sizeMB / 1024.0);
        } else {
            size = wxString::Format("%.2f MB", sizeMB);
        }
        list->SetItem(idx, 4, size);
        
        // Guardar índice en el vector de downloads como dato del item (offset por uploads)
        list->SetItemData(idx, incompleteUploads.size() + i);
        itemIndex++;
    }
    
    // Seleccionar primer item por defecto
    if (itemIndex > 0) {
        list->SetItemState(0, wxLIST_STATE_SELECTED, wxLIST_STATE_SELECTED);
    }
    
    sizer->Add(list, 1, wxALL | wxEXPAND, 15);
    
    // Botones de acción
    wxBoxSizer* buttonSizer = new wxBoxSizer(wxHORIZONTAL);
    
    wxButton* resumeBtn = new wxButton(dialog, wxID_ANY, "Resume Selected");
    wxButton* cancelBtn = new wxButton(dialog, wxID_ANY, "Cancel Selected");
    wxButton* closeBtn = new wxButton(dialog, wxID_CLOSE, "Close");
    
    resumeBtn->SetBackgroundColour(wxColour(46, 125, 50));
    resumeBtn->SetForegroundColour(*wxWHITE);
    wxFont btnFont = resumeBtn->GetFont();
    btnFont.SetPointSize(10);
    btnFont.SetWeight(wxFONTWEIGHT_BOLD);
    resumeBtn->SetFont(btnFont);
    resumeBtn->SetMinSize(wxSize(160, 35));
    
    cancelBtn->SetBackgroundColour(wxColour(211, 47, 47));
    cancelBtn->SetForegroundColour(*wxWHITE);
    cancelBtn->SetFont(btnFont);
    cancelBtn->SetMinSize(wxSize(160, 35));
    
    closeBtn->SetBackgroundColour(wxColour(69, 90, 100));
    closeBtn->SetForegroundColour(*wxWHITE);
    wxFont closeBtnFont = closeBtn->GetFont();
    closeBtnFont.SetPointSize(10);
    closeBtn->SetFont(closeBtnFont);
    closeBtn->SetMinSize(wxSize(120, 35));
    
    buttonSizer->Add(resumeBtn, 0, wxALL, 5);
    buttonSizer->Add(cancelBtn, 0, wxALL, 5);
    buttonSizer->AddStretchSpacer();
    buttonSizer->Add(closeBtn, 0, wxALL, 5);
    
    sizer->Add(buttonSizer, 0, wxALL | wxEXPAND, 15);
    
    // Event handler para reanudar
    resumeBtn->Bind(wxEVT_BUTTON, [this, list, incompleteUploads, incompleteDownloads, dialog](wxCommandEvent&) {
        long selected = list->GetNextItem(-1, wxLIST_NEXT_ALL, wxLIST_STATE_SELECTED);
        if (selected == -1) {
            wxMessageBox("Please select an operation to resume", "Info", 
                        wxOK | wxICON_INFORMATION);
            return;
        }
        
        long dataIndex = list->GetItemData(selected);
        wxString type = list->GetItemText(selected, 0);
        
        // Determinar si es upload o download
        if (type == "Upload" && dataIndex < (long)incompleteUploads.size()) {
            // === UPLOAD RESUME ===
            const auto& info = incompleteUploads[dataIndex];
            
            // Solicitar archivo original
            wxFileDialog fileDialog(dialog,
                "Select the original file: " + wxString(info.originalFilename),
                "", info.originalFilename,
                "*.*",
                wxFD_OPEN | wxFD_FILE_MUST_EXIST);
            
            if (fileDialog.ShowModal() == wxID_OK) {
                wxString filePath = fileDialog.GetPath();
                m_currentUploadId = info.fileId;
                m_uploadStatusLabel->SetLabel(wxString::Format("Resuming: %s", info.originalFilename));
                m_uploadStatusLabel->SetForegroundColour(wxColour(0, 200, 255));
                UpdateOperationControls(true, OperationType::UPLOAD);
                
                // Reanudar upload en thread separado
                std::thread([this, info, filePath]() {
                    ChunkedUpload upload(m_database.get(), m_telegramHandler.get(), m_telegramNotifier.get());
                    upload.setProgressCallback([this, info](int64_t completed, int64_t total, double percent) {
                        wxTheApp->CallAfter([this, info, completed, total, percent]() {
                            m_uploadProgress->SetValue((int)percent);
                            m_uploadStatusLabel->SetLabel(
                                wxString::Format("Uploading: %d%% (%lld/%lld chunks) - %s", 
                                               (int)percent, completed, total, info.originalFilename));
                            m_uploadStatusLabel->SetForegroundColour(wxColour(0, 255, 0));
                        });
                    });
                    
                    wxTheApp->CallAfter([this, info]() {
                        m_uploadProgress->Show();
                        m_uploadProgress->SetRange(100);
                        m_uploadProgress->SetValue(0);
                        m_uploadStatusLabel->SetLabel(wxString::Format("Resuming: %s", info.originalFilename));
                        m_uploadStatusLabel->SetForegroundColour(wxColour(0, 200, 255));
                        m_uploadStatusLabel->Show();
                    });
                    
                    std::string result = upload.resumeUpload(info.fileId, filePath.ToStdString());
                    
                    wxTheApp->CallAfter([this, result, info]() {
                        if (!result.empty()) {
                            m_currentUploadId = result;
                            m_uploadProgress->SetValue(100);
                            m_uploadStatusLabel->SetLabel(wxString::Format("Upload completed - %s", info.originalFilename));
                            m_uploadStatusLabel->SetForegroundColour(wxColour(100, 255, 100));
                        } else {
                            m_uploadStatusLabel->SetLabel(wxString::Format("Upload failed - %s", info.originalFilename));
                            m_uploadStatusLabel->SetForegroundColour(wxColour(255, 0, 0));
                        }
                        
                        std::this_thread::sleep_for(std::chrono::seconds(3));
                        wxTheApp->CallAfter([this]() {
                            m_uploadProgress->Hide();
                            m_uploadStatusLabel->SetLabel("Ready");
                            m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                            UpdateOperationControls(false);
                            m_currentUploadId.clear();
                        });
                    });
                }).detach();
                
                dialog->Close();
            }
        } else if (type == "Download") {
            // === DOWNLOAD RESUME ===
            size_t downloadIndex = dataIndex - incompleteUploads.size();
            if (downloadIndex < incompleteDownloads.size()) {
                const auto& info = incompleteDownloads[downloadIndex];
                
                // Solicitar directorio de destino
                wxDirDialog dirDialog(dialog, "Select destination directory",
                                     "", wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
                
                if (dirDialog.ShowModal() == wxID_OK) {
                    wxString destDir = dirDialog.GetPath();
                    wxString destPath = destDir + wxFileName::GetPathSeparator() + info.fileName;
                    
                    m_uploadStatusLabel->SetLabel(wxString::Format("Resuming download: %s", info.fileName));
                    m_uploadStatusLabel->SetForegroundColour(wxColour(0, 200, 255));
                    
                    // Reanudar download en thread separado
                    std::thread([this, info, destPath]() {
                        ChunkedDownload downloader(m_database.get(), m_telegramHandler.get(), m_telegramNotifier.get());
                        
                        downloader.setProgressCallback([this, info](int64_t completed, int64_t total, double percent) {
                            wxTheApp->CallAfter([this, info, completed, total, percent]() {
                                m_uploadProgress->SetValue((int)percent);
                                
                                if (completed < 0 && total < 0) {
                                    int64_t reconstructed = -completed;
                                    int64_t totalToReconstruct = -total;
                                    m_uploadStatusLabel->SetLabel(
                                        wxString::Format("Reconstructing: %d%% (%lld/%lld chunks) - %s", 
                                                       (int)percent, reconstructed, totalToReconstruct, info.fileName));
                                    m_uploadStatusLabel->SetForegroundColour(wxColour(255, 200, 0));
                                } else {
                                    m_uploadStatusLabel->SetLabel(
                                        wxString::Format("Downloading: %d%% (%lld/%lld chunks) - %s", 
                                                       (int)percent, completed, total, info.fileName));
                                    m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                                }
                            });
                        });
                        
                        wxTheApp->CallAfter([this, info]() {
                            m_uploadProgress->Show();
                            m_uploadProgress->SetRange(100);
                            m_uploadProgress->SetValue(0);
                            m_uploadStatusLabel->SetLabel(wxString::Format("Resuming download: %s", info.fileName));
                            m_uploadStatusLabel->SetForegroundColour(wxColour(0, 200, 255));
                            m_uploadStatusLabel->Show();
                        });
                        
                        std::string result = downloader.resumeDownload(info.downloadId, destPath.ToStdString());
                        
                        wxTheApp->CallAfter([this, result, info]() {
                            if (!result.empty()) {
                                m_uploadProgress->SetValue(100);
                                m_uploadStatusLabel->SetLabel(wxString::Format("Download completed - %s", info.fileName));
                                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 255, 100));
                            } else {
                                m_uploadStatusLabel->SetLabel(wxString::Format("Download failed - %s", info.fileName));
                                m_uploadStatusLabel->SetForegroundColour(wxColour(255, 0, 0));
                            }
                            
                            wxSleep(2);
                            m_uploadProgress->Hide();
                            m_uploadStatusLabel->SetLabel("Ready");
                            m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                        });
                    }).detach();
                    
                    dialog->Close();
                }
            }
        }
    });
    
    // Event handler para cancelar
    cancelBtn->Bind(wxEVT_BUTTON, [this, list, incompleteUploads, incompleteDownloads, dialog](wxCommandEvent&) {
        long selected = list->GetNextItem(-1, wxLIST_NEXT_ALL, wxLIST_STATE_SELECTED);
        if (selected == -1) {
            wxMessageBox("Please select an operation to cancel", "Info", 
                        wxOK | wxICON_INFORMATION);
            return;
        }
        
        long dataIndex = list->GetItemData(selected);
        wxString type = list->GetItemText(selected, 0);
        
        if (type == "Upload" && dataIndex < (long)incompleteUploads.size()) {
            // === CANCEL UPLOAD ===
            const auto& info = incompleteUploads[dataIndex];
            
            int response = wxMessageBox(
                wxString::Format("Cancel upload of '%s'?\n\nProgress: %lld/%lld chunks\n\nAll progress will be lost!",
                               info.originalFilename,
                               info.completedChunks,
                               info.totalChunks),
                "Confirm Cancellation",
                wxYES_NO | wxICON_WARNING);
            
            if (response == wxYES) {
                ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
                if (upload.cancelUpload(info.fileId)) {
                    wxMessageBox("Upload cancelled successfully", "Info", 
                                wxOK | wxICON_INFORMATION);
                    dialog->Close();
                } else {
                    wxMessageBox("Failed to cancel upload", "Error", 
                                wxOK | wxICON_ERROR);
                }
            }
        } else if (type == "Download") {
            // === CANCEL DOWNLOAD ===
            size_t downloadIndex = dataIndex - incompleteUploads.size();
            if (downloadIndex < incompleteDownloads.size()) {
                const auto& info = incompleteDownloads[downloadIndex];
                
                int response = wxMessageBox(
                    wxString::Format("Cancel download of '%s'?\n\nProgress: %lld/%lld chunks\n\nAll progress will be lost!",
                                   info.fileName,
                                   info.completedChunks,
                                   info.totalChunks),
                    "Confirm Cancellation",
                    wxYES_NO | wxICON_WARNING);
                
                if (response == wxYES) {
                    ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
                    if (downloader.cancelDownload(info.downloadId)) {
                        wxMessageBox("Download cancelled successfully", "Info", 
                                    wxOK | wxICON_INFORMATION);
                        dialog->Close();
                    } else {
                        wxMessageBox("Failed to cancel download", "Error", 
                                    wxOK | wxICON_ERROR);
                    }
                }
            }
        }
    });
    
    // Event handler para cerrar
    closeBtn->Bind(wxEVT_BUTTON, [dialog](wxCommandEvent&) {
        dialog->Close();
    });
    
    dialog->SetSizer(sizer);
    dialog->ShowModal();
    dialog->Destroy();
}

void MainWindow::CheckIncompleteUploadsOnStartup() {
    if (!m_database) return;
    
    ChunkedUpload upload(m_database.get(), m_telegramHandler.get());
    std::vector<ChunkedFileInfo> incompleteUploads = upload.getIncompleteUploads();
    
    if (!incompleteUploads.empty()) {
        wxString message = wxString::Format(
            "Found %d incomplete upload(s).\n\n"
            "Would you like to view them now?",
            (int)incompleteUploads.size());
        
        int response = wxMessageBox(message, "Incomplete Uploads",
                                   wxYES_NO | wxICON_QUESTION);
        
        if (response == wxYES) {
            wxCommandEvent evt;
            OnShowIncompleteUploads(evt);
        }
    }
}

void MainWindow::OnShowIncompleteDownloads(wxCommandEvent& event) {
    ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
    std::vector<DownloadInfo> incompleteDownloads = downloader.getIncompleteDownloads();
    
    if (incompleteDownloads.empty()) {
        wxMessageBox("No incomplete downloads found", "Info", 
                    wxOK | wxICON_INFORMATION);
        return;
    }
    
    wxDialog* dialog = new wxDialog(this, wxID_ANY, "Incomplete Downloads", 
                                   wxDefaultPosition, wxSize(900, 500));
    
    // Tema oscuro para el diálogo
    dialog->SetBackgroundColour(wxColour(30, 30, 30));
    
    wxBoxSizer* sizer = new wxBoxSizer(wxVERTICAL);
    
    // Título con estilo
    wxStaticText* title = new wxStaticText(dialog, wxID_ANY, "INCOMPLETE DOWNLOADS");
    wxFont titleFont = title->GetFont();
    titleFont.SetPointSize(16);
    titleFont.SetWeight(wxFONTWEIGHT_BOLD);
    title->SetFont(titleFont);
    title->SetForegroundColour(wxColour(100, 200, 100));
    sizer->Add(title, 0, wxALL | wxALIGN_CENTER_HORIZONTAL, 15);
    
    // Instrucciones
    wxStaticText* instructions = new wxStaticText(dialog, wxID_ANY, 
        "Select a download and click Resume to continue from where you left off:");
    instructions->SetForegroundColour(wxColour(180, 180, 180));
    sizer->Add(instructions, 0, wxLEFT | wxRIGHT | wxBOTTOM, 15);
    
    wxListCtrl* list = new wxListCtrl(dialog, wxID_ANY, 
                                     wxDefaultPosition, wxDefaultSize,
                                     wxLC_REPORT | wxLC_SINGLE_SEL);
    
    // Tema oscuro para la lista
    list->SetBackgroundColour(wxColour(40, 40, 40));
    list->SetForegroundColour(wxColour(220, 220, 220));
    
    list->InsertColumn(0, "File Name", wxLIST_FORMAT_LEFT, 300);
    list->InsertColumn(1, "Progress", wxLIST_FORMAT_CENTER, 180);
    list->InsertColumn(2, "Status", wxLIST_FORMAT_CENTER, 120);
    list->InsertColumn(3, "Size", wxLIST_FORMAT_RIGHT, 150);
    
    for (size_t i = 0; i < incompleteDownloads.size(); i++) {
        const auto& info = incompleteDownloads[i];
        
        long idx = list->InsertItem(i, info.fileName);
        
        wxString progress = wxString::Format("%lld/%lld chunks (%.1f%%)", 
            info.completedChunks, 
            info.totalChunks,
            (info.completedChunks * 100.0 / info.totalChunks));
        list->SetItem(idx, 1, progress);
        
        list->SetItem(idx, 2, info.status);
        
        double sizeMB = info.totalSize / 1024.0 / 1024.0;
        wxString size;
        if (sizeMB > 1024) {
            size = wxString::Format("%.2f GB", sizeMB / 1024.0);
        } else {
            size = wxString::Format("%.2f MB", sizeMB);
        }
        list->SetItem(idx, 3, size);
    }
    
    // Seleccionar primer item por defecto
    if (incompleteDownloads.size() > 0) {
        list->SetItemState(0, wxLIST_STATE_SELECTED, wxLIST_STATE_SELECTED);
    }
    
    sizer->Add(list, 1, wxALL | wxEXPAND, 15);
    
    // Botones de acción
    wxBoxSizer* buttonSizer = new wxBoxSizer(wxHORIZONTAL);
    
    wxButton* resumeBtn = new wxButton(dialog, wxID_ANY, "Resume Selected");
    wxButton* cancelBtn = new wxButton(dialog, wxID_ANY, "Cancel Selected");
    wxButton* closeBtn = new wxButton(dialog, wxID_CLOSE, "Close");
    
    resumeBtn->SetBackgroundColour(wxColour(46, 125, 50));
    resumeBtn->SetForegroundColour(*wxWHITE);
    wxFont btnFont = resumeBtn->GetFont();
    btnFont.SetPointSize(10);
    btnFont.SetWeight(wxFONTWEIGHT_BOLD);
    resumeBtn->SetFont(btnFont);
    resumeBtn->SetMinSize(wxSize(160, 35));
    
    cancelBtn->SetBackgroundColour(wxColour(211, 47, 47));
    cancelBtn->SetForegroundColour(*wxWHITE);
    cancelBtn->SetFont(btnFont);
    cancelBtn->SetMinSize(wxSize(160, 35));
    
    closeBtn->SetBackgroundColour(wxColour(69, 90, 100));
    closeBtn->SetForegroundColour(*wxWHITE);
    wxFont closeBtnFont = closeBtn->GetFont();
    closeBtnFont.SetPointSize(10);
    closeBtn->SetFont(closeBtnFont);
    closeBtn->SetMinSize(wxSize(120, 35));
    
    buttonSizer->Add(resumeBtn, 0, wxALL, 5);
    buttonSizer->Add(cancelBtn, 0, wxALL, 5);
    buttonSizer->AddStretchSpacer();
    buttonSizer->Add(closeBtn, 0, wxALL, 5);
    
    sizer->Add(buttonSizer, 0, wxALL | wxEXPAND, 15);
    
    // Event handler para reanudar
    resumeBtn->Bind(wxEVT_BUTTON, [this, list, incompleteDownloads, dialog](wxCommandEvent&) {
        long selected = list->GetNextItem(-1, wxLIST_NEXT_ALL, wxLIST_STATE_SELECTED);
        if (selected == -1) {
            wxMessageBox("Please select a download to resume", "Info", 
                        wxOK | wxICON_INFORMATION);
            return;
        }
        
        const auto& info = incompleteDownloads[selected];
        
        // Solicitar directorio de destino
        wxDirDialog dirDialog(dialog,
            "Select destination directory for: " + wxString(info.fileName),
            "",
            wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
        
        if (dirDialog.ShowModal() == wxID_OK) {
            wxString destDir = dirDialog.GetPath();
            wxString destPath = destDir + wxFileName::GetPathSeparator() + info.fileName;
            
            m_uploadStatusLabel->SetLabel(wxString::Format("Resuming download: %s", info.fileName));
            m_uploadStatusLabel->SetForegroundColour(wxColour(0, 200, 255));
            
            // Reanudar en thread separado
            std::thread([this, info, destPath]() {
                ChunkedDownload downloader(m_database.get(), m_telegramHandler.get(), m_telegramNotifier.get());
                
                // Configurar callback de progreso
                downloader.setProgressCallback([this, info](int64_t completed, int64_t total, double percent) {
                    wxTheApp->CallAfter([this, info, completed, total, percent]() {
                        m_uploadProgress->SetValue((int)percent);
                        
                        // Valores negativos indican reconstrucción
                        if (completed < 0 && total < 0) {
                            int64_t reconstructed = -completed;
                            int64_t totalToReconstruct = -total;
                            m_uploadStatusLabel->SetLabel(
                                wxString::Format("Reconstructing: %d%% (%lld/%lld chunks) - %s", 
                                               (int)percent, reconstructed, totalToReconstruct, info.fileName)
                            );
                            m_uploadStatusLabel->SetForegroundColour(wxColour(255, 200, 0));
                        } else {
                            m_uploadStatusLabel->SetLabel(
                                wxString::Format("Downloading: %d%% (%lld/%lld chunks) - %s", 
                                               (int)percent, completed, total, info.fileName)
                            );
                            m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
                        }
                    });
                });
                
                // Mostrar barra de progreso
                wxTheApp->CallAfter([this, info]() {
                    m_uploadProgress->Show();
                    m_uploadProgress->SetRange(100);
                    m_uploadProgress->SetValue(0);
                    m_uploadStatusLabel->SetLabel(
                        wxString::Format("Resuming download: %s", info.fileName)
                    );
                    m_uploadStatusLabel->SetForegroundColour(wxColour(0, 200, 255));
                    m_uploadStatusLabel->Show();
                });
                
                std::string result = downloader.resumeDownload(info.downloadId, destPath.ToStdString());
                
                if (!result.empty()) {
                    LOG_INFO("Download resumed successfully");
                    wxTheApp->CallAfter([this, info]() {
                        m_uploadProgress->SetValue(100);
                        m_uploadStatusLabel->SetLabel(
                            wxString::Format("Download completed - %s", info.fileName)
                        );
                        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 255, 100));
                    });
                } else {
                    LOG_ERROR("Failed to resume download");
                    wxTheApp->CallAfter([this, info]() {
                        m_uploadStatusLabel->SetLabel(
                            wxString::Format("Download failed - %s", info.fileName)
                        );
                        m_uploadStatusLabel->SetForegroundColour(wxColour(255, 0, 0));
                    });
                }
                
                // Ocultar después de 3 segundos
                wxTheApp->CallAfter([this]() {
                    std::this_thread::sleep_for(std::chrono::seconds(3));
                    wxTheApp->CallAfter([this]() {
                        m_uploadProgress->Hide();
                        m_uploadStatusLabel->SetLabel("Ready");
                        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                    });
                });
            }).detach();
            
            dialog->Close();
        }
    });
    
    // Event handler para cancelar
    cancelBtn->Bind(wxEVT_BUTTON, [this, list, incompleteDownloads, dialog](wxCommandEvent&) {
        long selected = list->GetNextItem(-1, wxLIST_NEXT_ALL, wxLIST_STATE_SELECTED);
        if (selected == -1) {
            wxMessageBox("Please select a download to cancel", "Info", 
                        wxOK | wxICON_INFORMATION);
            return;
        }
        
        const auto& info = incompleteDownloads[selected];
        
        int response = wxMessageBox(
            wxString::Format("Cancel download of '%s'?\n\nProgress: %lld/%lld chunks\n\nAll progress will be lost!",
                           info.fileName,
                           info.completedChunks,
                           info.totalChunks),
            "Confirm Cancellation",
            wxYES_NO | wxICON_WARNING);
        
        if (response == wxYES) {
            ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
            if (downloader.cancelDownload(info.downloadId)) {
                wxMessageBox("Download cancelled successfully", "Info", 
                            wxOK | wxICON_INFORMATION);
                dialog->Close();
            } else {
                wxMessageBox("Failed to cancel download", "Error", 
                            wxOK | wxICON_ERROR);
            }
        }
    });
    
    // Event handler para cerrar
    closeBtn->Bind(wxEVT_BUTTON, [dialog](wxCommandEvent&) {
        dialog->Close();
    });
    
    dialog->SetSizer(sizer);
    dialog->ShowModal();
    dialog->Destroy();
}

void MainWindow::CheckIncompleteDownloadsOnStartup() {
    if (!m_database) return;
    
    ChunkedDownload downloader(m_database.get(), m_telegramHandler.get());
    std::vector<DownloadInfo> incompleteDownloads = downloader.getIncompleteDownloads();
    
    if (!incompleteDownloads.empty()) {
        wxString message = wxString::Format(
            "Found %d incomplete download(s).\n\n"
            "Would you like to view them now?",
            (int)incompleteDownloads.size());
        
        int response = wxMessageBox(message, "Incomplete Downloads",
                                   wxYES_NO | wxICON_QUESTION);
        
        if (response == wxYES) {
            wxCommandEvent evt;
            OnShowIncompleteDownloads(evt);
        }
    }
}

void MainWindow::UpdateOperationControls(bool active, OperationType type) {
    if (active) {
        m_currentOperationType = type;
    } else {
        m_currentOperationType = OperationType::NONE;
    }
    
    m_pauseButton->Enable(active);
    m_resumeButton->Enable(!active && m_currentOperationType != OperationType::NONE);
    m_stopButton->Enable(active);
    m_cancelButton->Enable(active || m_currentOperationType != OperationType::NONE);
}

} // namespace TelegramCloud
