#include "mainwindow.h"
#include "config.h"
#include "database.h"
#include "telegramhandler.h"
#include "chunkedupload.h"
#include "logger.h"
#include <wx/filedlg.h>
#include <wx/msgdlg.h>
#include <wx/dirdlg.h>
#include <wx/filename.h>
#include <wx/textdlg.h>
#include <wx/clipbrd.h>
#include <wx/timer.h>
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
#include <nlohmann/json.hpp>

namespace TelegramCloud {

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
    EVT_BUTTON(ID_TEST_CONNECTION, MainWindow::OnTestConnection)
    EVT_MENU(ID_SORT_BY_NAME, MainWindow::OnSortByName)
    EVT_MENU(ID_SORT_BY_SIZE, MainWindow::OnSortBySize)
    EVT_MENU(ID_SORT_BY_DATE, MainWindow::OnSortByDate)
    EVT_MENU(ID_SORT_BY_TYPE, MainWindow::OnSortByType)
EVT_MENU(ID_SORT_ASCENDING, MainWindow::OnSortAscending)
EVT_MENU(ID_SORT_DESCENDING, MainWindow::OnSortDescending)
EVT_MENU(ID_CONFIG, MainWindow::OnConfig)
EVT_MENU(ID_DECRYPT_FILE, MainWindow::OnDecryptFile)
EVT_TIMER(ID_ANIMATION_TIMER, MainWindow::OnAnimationTimer)
    EVT_LIST_ITEM_ACTIVATED(wxID_ANY, MainWindow::OnListItemActivated)
    EVT_LIST_ITEM_SELECTED(wxID_ANY, MainWindow::OnListItemClick)
    EVT_LIST_ITEM_DESELECTED(wxID_ANY, MainWindow::OnListItemClick)
    EVT_MENU(wxID_EXIT, MainWindow::OnQuit)
    EVT_MENU(wxID_ABOUT, MainWindow::OnAbout)
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
    m_animationTimer(nullptr)
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
        LOG_INFO("Skipping component initialization - running in limited mode");
    }
    
    LOG_INFO("MainWindow initialized successfully");
    
    CreateMenuBar();
    CreateControls();
    CreateStatusBar();
    
    Centre();
    
    if (m_configValid) {
        LoadFiles();
        UpdateStats();
    } else {
        // Update status to show limited mode
        if (m_serverStatusLabel) {
            m_serverStatusLabel->SetLabel("Status: Limited Mode (Configure to enable full features)");
            m_serverStatusLabel->SetForegroundColour(wxColour(255, 165, 0)); // Orange
        }
    }
}

MainWindow::~MainWindow() {
    // Limpiar timer
    if (m_animationTimer) {
        m_animationTimer->Stop();
        delete m_animationTimer;
    }
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
    
    wxMenu* helpMenu = new wxMenu;
    helpMenu->Append(wxID_ABOUT);
    
    menuBar->Append(fileMenu, "&File");
    menuBar->Append(viewMenu, "&View");
    menuBar->Append(configMenu, "&Config");
    menuBar->Append(helpMenu, "&Help");
    
    SetMenuBar(menuBar);
}

void MainWindow::CreateControls() {
    m_mainPanel = new wxPanel(this);
    m_mainPanel->SetBackgroundColour(wxColour(45, 45, 45));
    wxBoxSizer* mainSizer = new wxBoxSizer(wxVERTICAL);
    
    
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
    
    uploadButtonsSizer->Add(m_uploadButton, 0, wxALL, 5);
    uploadButtonsSizer->Add(m_uploadMultipleButton, 0, wxALL, 5);
    uploadButtonsSizer->Add(m_downloadFromLinkButton, 0, wxALL, 5);
    
    uploadBox->Add(uploadButtonsSizer, 0, wxALL, 5);
    
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
    
    filesBox->Add(m_filesListCtrl, 1, wxEXPAND | wxALL, 5);
    filesBox->Add(filesButtonsSizer, 0, wxALL, 5);
    filesBox->Add(progressSizer, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 5);
    
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
    
    m_testConnectionButton = new wxButton(m_mainPanel, ID_TEST_CONNECTION, "Test Connection", wxDefaultPosition, wxSize(130, 32));
    m_testConnectionButton->SetBackgroundColour(wxColour(60, 60, 60));
    m_testConnectionButton->SetForegroundColour(*wxWHITE);
    
    statsBox->Add(m_totalFilesLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 10);
    statsBox->Add(m_totalStorageLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 10);
    statsBox->Add(m_serverStatusLabel, 0, wxALL | wxALIGN_CENTER_VERTICAL, 10);
    statsBox->AddStretchSpacer();
    statsBox->Add(m_testConnectionButton, 0, wxALL, 5);
    
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
    int64_t fileSize = file.is_open() ? file.tellg() : 0;
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
                    ChunkedUpload chunkedUpload(m_database.get(), m_telegramHandler.get());
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
    // Verificar si hay archivos seleccionados con checkbox
    if (m_selectedItems.empty()) {
        wxMessageBox("Please select one or more files to download",
                    "Download", wxOK | wxICON_INFORMATION);
        return;
    }
    
    LOG_INFO("Starting batch download for " + std::to_string(m_selectedItems.size()) + " files");
    
    // Seleccionar directorio de destino
    wxDirDialog dirDialog(this, "Choose download location", "",
                         wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
    
    if (dirDialog.ShowModal() == wxID_CANCEL) {
        LOG_INFO("Download canceled by user");
        return;
    }
    
    wxString destDir = dirDialog.GetPath();
    
    // Recopilar información de archivos a descargar
    std::vector<std::tuple<std::string, FileInfo, std::string>> filesToDownload;
    bool hasEncryptedFiles = false;
    
    for (long index : m_selectedItems) {
        auto it = m_itemToFileId.find(index);
        if (it != m_itemToFileId.end()) {
            try {
                FileInfo fileInfo = m_database->getFileInfo(it->second);
                if (!fileInfo.fileId.empty()) {
                    filesToDownload.push_back({it->second, fileInfo, ""});
                    if (fileInfo.isEncrypted) {
                        hasEncryptedFiles = true;
                    }
                }
            } catch (...) {
                LOG_ERROR("Failed to get file info for: " + it->second);
            }
        }
    }
    
    // Si hay archivos encriptados, solicitar contraseña
    std::string decryptionPassword;
    if (hasEncryptedFiles) {
        wxString password = wxGetPasswordFromUser(
            "Some files are encrypted. Enter the decryption password:",
            "Decrypt Files",
            "",
            this
        );
        
        if (password.IsEmpty()) {
            LOG_INFO("Download canceled - no password provided for encrypted files");
            return;
        }
        
        decryptionPassword = std::string(password.mb_str());
    }
    
    // Mostrar progreso
    m_uploadProgress->SetValue(0);
    m_uploadProgress->Show();
    m_uploadStatusLabel->SetLabel("Downloading files...");
    m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
    
    // Ejecutar descarga en thread separado
    std::thread downloadThread([this, filesToDownload, destDir, decryptionPassword]() {
        int totalFiles = filesToDownload.size();
        int successfulDownloads = 0;
        int failedDownloads = 0;
        
        for (int i = 0; i < totalFiles; ++i) {
            const auto& [fileId, fileInfo, _] = filesToDownload[i];
            
            // Actualizar progreso
            int progress = (i * 100) / totalFiles;
            wxTheApp->CallAfter([this, progress, i, totalFiles, fileInfo]() {
                m_uploadProgress->SetValue(progress);
                m_uploadStatusLabel->SetLabel(wxString::Format("Downloading %d/%d: %s", 
                    i + 1, totalFiles, wxString::FromUTF8(fileInfo.fileName)));
                m_uploadStatusLabel->SetForegroundColour(*wxWHITE);
            });
            
            try {
                bool success = false;
                std::string savePath = std::string(destDir.mb_str());
                
                // Descargar según tipo
                if (fileInfo.category == "chunked") {
                    auto chunks = m_database->getFileChunks(fileId);
                    if (!chunks.empty()) {
                        success = downloadChunkedFile(fileId, chunks, savePath, 
                                                     fileInfo.isEncrypted ? decryptionPassword : "");
                    }
                } else {
                    success = downloadDirectFile(fileInfo, savePath, 
                                                fileInfo.isEncrypted ? decryptionPassword : "");
                }
                
                if (success) {
                    successfulDownloads++;
                    LOG_INFO("Download successful: " + fileInfo.fileName);
                } else {
                    failedDownloads++;
                    LOG_ERROR("Download failed: " + fileInfo.fileName);
                }
                
            } catch (const std::exception& e) {
                LOG_ERROR("Exception during download of " + fileInfo.fileName + ": " + e.what());
                failedDownloads++;
            }
        }
        
        // Actualizar UI final
        wxTheApp->CallAfter([this, successfulDownloads, failedDownloads, totalFiles]() {
            m_uploadProgress->Hide();
            m_uploadStatusLabel->SetLabel("Ready");
            m_selectedItems.clear();
            
            wxString msg;
            if (failedDownloads == 0) {
                msg = wxString::Format(
                    "Files downloaded successfully!\n\n"
                    "Total: %d files",
                    successfulDownloads
                );
                wxMessageBox(msg, "Download Successful", wxOK | wxICON_INFORMATION);
            } else {
                msg = wxString::Format(
                    "Batch download completed with errors\n\n"
                    "Successful: %d\n"
                    "Failed: %d\n\n"
                    "Check logs for details.",
                    successfulDownloads, failedDownloads
                );
                wxMessageBox(msg, "Download Completed", wxOK | wxICON_WARNING);
            }
            
            LOG_INFO("Batch download completed: " + std::to_string(successfulDownloads) + " successful, " + std::to_string(failedDownloads) + " failed");
        });
    });
    
    downloadThread.detach();
    LOG_INFO("Batch download started in background thread");
}

void MainWindow::OnShare(wxCommandEvent& event) {
    // Verificar si hay archivos seleccionados con checkbox
    if (m_selectedItems.empty()) {
        wxMessageBox("Please select one or more files to share",
                    "Share", wxOK | wxICON_INFORMATION);
        return;
    }
    
    LOG_INFO("Starting batch share for " + std::to_string(m_selectedItems.size()) + " files");
    
    // Recopilar información de archivos a compartir
    std::vector<std::string> fileIds;
    std::vector<FileInfo> fileInfos;
    
    for (long index : m_selectedItems) {
        auto it = m_itemToFileId.find(index);
        if (it != m_itemToFileId.end()) {
            try {
                FileInfo fileInfo = m_database->getFileInfo(it->second);
                if (!fileInfo.fileId.empty()) {
                    fileIds.push_back(it->second);
                    fileInfos.push_back(fileInfo);
                }
            } catch (...) {
                LOG_ERROR("Failed to get file info for: " + it->second);
            }
        }
    }
    
    if (fileIds.empty()) {
        wxMessageBox("No valid files to share.", "Share Error", wxOK | wxICON_ERROR);
        return;
    }
    
    // Crear enlace compartido para múltiples archivos
    try {
        // Crear JSON con información de múltiples archivos
        std::string jsonData = "{\"files\":[";
        
        for (size_t i = 0; i < fileIds.size(); ++i) {
            if (i > 0) jsonData += ",";
            jsonData += "{";
            jsonData += "\"id\":\"" + fileIds[i] + "\",";
            jsonData += "\"name\":\"" + fileInfos[i].fileName + "\",";
            jsonData += "\"size\":" + std::to_string(fileInfos[i].fileSize) + ",";
            jsonData += "\"encrypted\":" + (fileInfos[i].isEncrypted ? "true" : "false");
            jsonData += "}";
        }
        
        jsonData += "],\"type\":\"multiple\"}";
        
        // Encriptar el enlace
        std::string encryptedLink = aesEncrypt(jsonData, "default_share_password");
        
        // Mostrar enlace
        wxString linkText = wxString::FromUTF8(encryptedLink);
        
        wxDialog* linkDialog = new wxDialog(this, wxID_ANY, "Share Link", wxDefaultPosition, wxSize(600, 400));
        wxBoxSizer* sizer = new wxBoxSizer(wxVERTICAL);
        
        wxStaticText* infoLabel = new wxStaticText(linkDialog, wxID_ANY, 
            wxString::Format("Share link for %d files:", (int)fileIds.size()));
        infoLabel->SetForegroundColour(*wxWHITE);
        sizer->Add(infoLabel, 0, wxALL, 10);
        
        wxTextCtrl* linkCtrl = new wxTextCtrl(linkDialog, wxID_ANY, linkText, 
            wxDefaultPosition, wxDefaultSize, wxTE_MULTILINE | wxTE_READONLY);
        linkCtrl->SetBackgroundColour(wxColour(50, 50, 50));
        linkCtrl->SetForegroundColour(*wxWHITE);
        sizer->Add(linkCtrl, 1, wxEXPAND | wxALL, 10);
        
        wxButton* copyBtn = new wxButton(linkDialog, wxID_ANY, "Copy Link");
        copyBtn->Bind(wxEVT_BUTTON, [linkCtrl](wxCommandEvent&) {
            if (wxTheClipboard->Open()) {
                wxTheClipboard->SetData(new wxTextDataObject(linkCtrl->GetValue()));
                wxTheClipboard->Close();
                wxMessageBox("Link copied to clipboard!", "Copy", wxOK | wxICON_INFORMATION);
            }
        });
        sizer->Add(copyBtn, 0, wxALL, 10);
        
        wxButton* closeBtn = new wxButton(linkDialog, wxID_ANY, "Close");
        closeBtn->Bind(wxEVT_BUTTON, [linkDialog](wxCommandEvent&) {
            linkDialog->Close();
        });
        sizer->Add(closeBtn, 0, wxALL, 10);
        
        linkDialog->SetSizer(sizer);
        linkDialog->ShowModal();
        linkDialog->Destroy();
        
        LOG_INFO("Share link created for " + std::to_string(fileIds.size()) + " files");
        
    } catch (const std::exception& e) {
        LOG_ERROR("Error creating share link: " + std::string(e.what()));
        wxMessageBox("Error creating share link:\n\n" + std::string(e.what()), 
                    "Share Error", wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnDelete(wxCommandEvent& event) {
    // Verificar si hay archivos seleccionados con checkbox
    if (m_selectedItems.empty()) {
        wxMessageBox("Please select one or more files to delete",
                    "Delete", wxOK | wxICON_INFORMATION);
        return;
    }
    
    // Mensaje de confirmación
    int fileCount = m_selectedItems.size();
    wxString confirmMsg;
    if (fileCount == 1) {
        long index = *m_selectedItems.begin();
        wxString fileName = m_filesListCtrl->GetItemText(index, 1);
        confirmMsg = "Are you sure you want to delete '" + fileName + "'?\n\nThis will permanently remove the file from both Telegram and the local database.";
    } else {
        confirmMsg = wxString::Format("Are you sure you want to delete %d files?\n\nThis will permanently remove them from both Telegram and the local database.", fileCount);
    }
    
    int ret = wxMessageBox(confirmMsg, "Delete Files", wxYES_NO | wxICON_QUESTION);
    
    if (ret == wxYES) {
        // Recopilar todos los file IDs
        std::vector<std::pair<long, std::string>> filesToDelete;
        for (long index : m_selectedItems) {
            auto it = m_itemToFileId.find(index);
            if (it != m_itemToFileId.end()) {
                wxString fileName = m_filesListCtrl->GetItemText(index, 1);
                filesToDelete.push_back({index, it->second});
                LOG_INFO("Queued for deletion: " + fileName.ToStdString() + " (ID: " + it->second + ")");
            }
        }
        
        if (filesToDelete.empty()) {
            wxMessageBox("No valid files to delete.", "Delete Error", wxOK | wxICON_ERROR);
            return;
        }
        
        LOG_INFO("Starting batch delete operation for " + std::to_string(filesToDelete.size()) + " files");
        
        // Mostrar progreso
        m_uploadProgress->Show();
        m_uploadProgress->SetValue(0);
        m_uploadStatusLabel->SetLabel("Deleting files...");
        m_uploadStatusLabel->Show();
        
        // Ejecutar eliminación en hilo separado
        std::thread deleteThread([this, filesToDelete]() {
            try {
                int totalFiles = filesToDelete.size();
                int successfulDeletes = 0;
                int failedDeletes = 0;
                
                for (int i = 0; i < totalFiles; ++i) {
                    const auto& [index, fileId] = filesToDelete[i];
                    
                    // Actualizar progreso
                    int progress = (i * 100) / totalFiles;
                    wxTheApp->CallAfter([this, progress, i, totalFiles]() {
                        m_uploadProgress->SetValue(progress);
                        m_uploadStatusLabel->SetLabel(wxString::Format("Deleting %d/%d...", i + 1, totalFiles));
                    });
                    
                    bool fileSuccess = true;
                    
                    // 1. Obtener mensajes a eliminar de Telegram
                    auto messagesToDelete = m_database->getMessagesToDelete(fileId);
                    
                    LOG_INFO("Found " + std::to_string(messagesToDelete.size()) + " messages to delete for file: " + fileId);
                    
                    // 2. Eliminar mensajes de Telegram
                    for (const auto& msg : messagesToDelete) {
                        int64_t messageId = msg.first;
                        std::string botToken = msg.second;
                        
                        if (!m_telegramHandler->deleteMessage(messageId, botToken)) {
                            LOG_WARNING("Failed to delete message " + std::to_string(messageId) + " from Telegram");
                        }
                    }
                    
                    // 3. Eliminar de la base de datos
                    if (!m_database->deleteFile(fileId)) {
                        LOG_ERROR("Failed to delete file from database: " + fileId);
                        fileSuccess = false;
                        failedDeletes++;
                    } else {
                        successfulDeletes++;
                    }
                }
                
                // 4. Actualizar interfaz
                wxTheApp->CallAfter([this, successfulDeletes, failedDeletes, totalFiles]() {
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
                    if (failedDeletes == 0) {
                        msg = wxString::Format(
                            "Files deleted successfully!\n\n"
                            "Total: %d files deleted",
                            successfulDeletes
                        );
                        wxMessageBox(msg, "Delete Successful", wxOK | wxICON_INFORMATION);
                    } else {
                        msg = wxString::Format(
                            "Batch delete completed with errors\n\n"
                            "Successful: %d\n"
                            "Failed: %d\n\n"
                            "Check logs for details.",
                            successfulDeletes, failedDeletes
                        );
                        wxMessageBox(msg, "Delete Completed", wxOK | wxICON_WARNING);
                    }
                    
                    LOG_INFO("Batch delete completed: " + std::to_string(successfulDeletes) + " successful, " + std::to_string(failedDeletes) + " failed");
                });
                
            } catch (const std::exception& e) {
                LOG_CRITICAL("Exception in delete thread: " + std::string(e.what()));
                
                wxTheApp->CallAfter([this, e]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    m_selectedItems.clear();
                    
                    wxString msg = wxString::Format(
                        "Delete crashed with exception:\n\n%s\n\n"
                        "Check logs for details.",
                        e.what()
                    );
                    
                    wxMessageBox(msg, "Delete Error", wxOK | wxICON_ERROR);
                });
            } catch (...) {
                LOG_CRITICAL("Unknown exception in delete thread");
                
                wxTheApp->CallAfter([this]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    m_selectedItems.clear();
                    
                    wxMessageBox("Delete crashed with unknown error.\n\nCheck logs for details.",
                                "Delete Error", wxOK | wxICON_ERROR);
                });
            }
        });
        
        deleteThread.detach();
        LOG_INFO("Delete started in background thread");
    }
}

void MainWindow::OnDownloadFromLink(wxCommandEvent& event) {
    wxString encryptedLink = wxGetTextFromUser(
        "Enter the encrypted share link:",
        "Download from Link",
        "",
        this
    );
    
    if (encryptedLink.IsEmpty()) {
        LOG_INFO("Download from link canceled by user");
        return;
    }
    
    try {
        // Desencriptar el enlace
        std::string decryptedData = aesDecrypt(encryptedLink.ToStdString(), "default_share_password");
        
        // Parsear JSON
        nlohmann::json jsonData = nlohmann::json::parse(decryptedData);
        
        if (jsonData.contains("type") && jsonData["type"] == "multiple") {
            // Múltiples archivos
            wxMessageBox("Multiple file download from share link is not yet implemented.",
                        "Feature Not Available", wxOK | wxICON_INFORMATION);
            return;
        }
        
        // Archivo simple
        std::string fileId = jsonData["id"];
        std::string fileName = jsonData["name"];
        bool isEncrypted = jsonData.value("encrypted", false);
        
        // Solicitar contraseña si está encriptado
        std::string decryptionPassword;
        if (isEncrypted) {
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
        }
        
        // Seleccionar directorio de destino
        wxDirDialog dirDialog(this, "Choose download location", "",
                             wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
        
        if (dirDialog.ShowModal() == wxID_CANCEL) {
            LOG_INFO("Download canceled by user");
            return;
        }
        
        wxString destDir = dirDialog.GetPath();
        wxString destPath = destDir + wxFileName::GetPathSeparator() + wxString::FromUTF8(fileName);
        
        // Obtener información del archivo
        FileInfo fileInfo = m_database->getFileInfo(fileId);
        if (fileInfo.fileId.empty()) {
            wxMessageBox("File not found in database.", "Download Error", wxOK | wxICON_ERROR);
            return;
        }
        
        // Descargar archivo
        bool success = false;
        if (fileInfo.category == "chunked") {
            auto chunks = m_database->getFileChunks(fileId);
            if (chunks.empty()) {
                wxMessageBox("No chunks found for this file.", "Download Error", wxOK | wxICON_ERROR);
                return;
            }
            
            success = downloadChunkedFile(fileId, chunks, destPath.ToStdString(), decryptionPassword);
        } else {
            success = downloadDirectFile(fileInfo, destPath.ToStdString(), decryptionPassword);
        }
        
        if (success) {
            wxMessageBox("Download completed successfully!", "Download Success", wxOK | wxICON_INFORMATION);
        } else {
            wxMessageBox("Download failed. Check logs for details.", "Download Error", wxOK | wxICON_ERROR);
        }
        
    } catch (const std::exception& e) {
        LOG_ERROR("Error processing share link: " + std::string(e.what()));
        wxMessageBox("Error processing share link:\n\n" + std::string(e.what()), 
                    "Share Link Error", wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnTestConnection(wxCommandEvent& event) {
    LOG_INFO("Testing Telegram connection...");
    m_testConnectionButton->Enable(false);
    SetStatusText("Testing connection...");
    
    // Test en thread separado
    std::thread testThread([this]() {
        bool success = m_telegramHandler->testConnection();
        
        wxTheApp->CallAfter([this, success]() {
            m_testConnectionButton->Enable(true);
            
            if (success) {
                SetStatusText("Connection test successful!");
                wxMessageBox("Telegram connection test successful!", "Connection Test", wxOK | wxICON_INFORMATION);
            } else {
                SetStatusText("Connection test failed!");
                wxMessageBox("Telegram connection test failed. Check your configuration.", "Connection Test", wxOK | wxICON_ERROR);
            }
        });
    });
    
    testThread.detach();
}

void MainWindow::OnQuit(wxCommandEvent& event) {
    Close(true);
}

void MainWindow::OnAbout(wxCommandEvent& event) {
    wxMessageBox("Telegram Cloud Desktop\n\nVersion 1.0.0\n\nA secure file storage solution using Telegram as backend.", 
                "About", wxOK | wxICON_INFORMATION);
}

void MainWindow::OnDecryptFile(wxCommandEvent& event) {
    // Implementación del botón de desencriptación manual
    long selected = m_filesListCtrl->GetNextItem(-1, wxLIST_NEXT_ALL, wxLIST_STATE_SELECTED);
    if (selected == -1) {
        wxMessageBox("Please select a file to decrypt", "Decrypt File", wxOK | wxICON_INFORMATION);
        return;
    }
    
    auto it = m_itemToFileId.find(selected);
    if (it == m_itemToFileId.end()) {
        wxMessageBox("File ID not found. Try refreshing the list.", "Decrypt Error", wxOK | wxICON_ERROR);
        return;
    }
    
    FileInfo fileInfo = m_database->getFileInfo(it->second);
    if (!fileInfo.isEncrypted) {
        wxMessageBox("This file is not encrypted.", "Decrypt File", wxOK | wxICON_INFORMATION);
        return;
    }
    
    // Seleccionar archivo para desencriptar
    wxFileDialog openDialog(this, "Select encrypted file to decrypt", "", "", 
                           "All files (*.*)|*.*", wxFD_OPEN | wxFD_FILE_MUST_EXIST);
    
    if (openDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    wxString inputPath = openDialog.GetPath();
    
    // Seleccionar destino
    wxFileDialog saveDialog(this, "Save decrypted file as", "", fileInfo.fileName, 
                           "All files (*.*)|*.*", wxFD_SAVE | wxFD_OVERWRITE_PROMPT);
    
    if (saveDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    wxString outputPath = saveDialog.GetPath();
    
    // Solicitar contraseña
    wxString password = wxGetPasswordFromUser(
        "Enter the decryption password:",
        "Decrypt File",
        "",
        this
    );
    
    if (password.IsEmpty()) {
        return;
    }
    
    // Desencriptar
    bool success = decryptFile(inputPath.ToStdString(), outputPath.ToStdString(), password.ToStdString());
    
    if (success) {
        wxMessageBox("File decrypted successfully!", "Decrypt Success", wxOK | wxICON_INFORMATION);
    } else {
        wxMessageBox("Decryption failed. Wrong password or corrupted file?", "Decrypt Error", wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnAnimationTimer(wxTimerEvent& event) {
    if (!m_showDotAnimation) {
        return;
    }
    
    m_dotAnimationCounter++;
    if (m_dotAnimationCounter > 3) {
        m_dotAnimationCounter = 0;
    }
    
    wxString dots;
    for (int i = 0; i < m_dotAnimationCounter; i++) {
        dots += ".";
    }
    
    if (m_uploadStatusLabel) {
        m_uploadStatusLabel->SetLabel("Uploading" + dots);
    }
}

void MainWindow::LoadFiles() {
    if (!m_database) {
        return;
    }
    
    // Limpiar lista actual
    m_filesListCtrl->DeleteAllItems();
    m_itemToFileId.clear();
    m_selectedItems.clear();
    
    // Obtener archivos de la base de datos
    std::vector<FileInfo> files = m_database->getFiles();
    
    LOG_INFO("Loading " + std::to_string(files.size()) + " files into UI (search: '" + m_currentSearch + "', sort: " + m_currentSortBy + ")");
    
    for (const auto& file : files) {
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

// Métodos de encriptación/desencriptación
bool MainWindow::encryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password) {
    try {
        // Leer archivo completo
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) {
            LOG_ERROR("Failed to open input file: " + inputPath);
            return false;
        }
        
        std::string plaintext((std::istreambuf_iterator<char>(inFile)), std::istreambuf_iterator<char>());
        inFile.close();
        
        // Generar salt aleatorio
        std::string salt = generateRandomSalt();
        
        // Derivar clave
        std::string key = deriveKey(password, salt);
        
        // Crear IV aleatorio
        unsigned char iv[AES_BLOCK_SIZE];
        if (RAND_bytes(iv, AES_BLOCK_SIZE) != 1) {
            LOG_ERROR("Failed to generate random IV");
            return false;
        }
        
        // Encriptar
        std::string encrypted = aesEncrypt(plaintext, key);
        if (encrypted.empty()) {
            LOG_ERROR("Failed to encrypt file content");
            return false;
        }
        
        // Escribir archivo encriptado: salt + iv + encrypted_data
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to create output file: " + outputPath);
            return false;
        }
        
        // Escribir salt (16 bytes)
        outFile.write(salt.c_str(), 16);
        
        // Escribir IV (16 bytes)
        outFile.write(reinterpret_cast<const char*>(iv), AES_BLOCK_SIZE);
        
        // Escribir datos encriptados
        outFile.write(encrypted.c_str(), encrypted.size());
        
        outFile.close();
        
        LOG_INFO("File encrypted successfully: " + inputPath + " -> " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during file encryption: " + std::string(e.what()));
        return false;
    }
}

bool MainWindow::decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password) {
    try {
        // Leer archivo encriptado
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) {
            LOG_ERROR("Failed to open input file: " + inputPath);
            return false;
        }
        
        // Leer salt (16 bytes)
        std::string salt(16, 0);
        inFile.read(&salt[0], 16);
        
        // Leer IV (16 bytes)
        unsigned char iv[AES_BLOCK_SIZE];
        inFile.read(reinterpret_cast<char*>(iv), AES_BLOCK_SIZE);
        
        // Leer datos encriptados
        std::string encrypted((std::istreambuf_iterator<char>(inFile)), std::istreambuf_iterator<char>());
        inFile.close();
        
        // Derivar clave
        std::string key = deriveKey(password, salt);
        
        // Desencriptar
        std::string plaintext = aesDecrypt(encrypted, key);
        if (plaintext.empty()) {
            LOG_ERROR("Failed to decrypt file content");
            return false;
        }
        
        // Escribir archivo desencriptado
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to create output file: " + outputPath);
            return false;
        }
        
        outFile.write(plaintext.c_str(), plaintext.size());
        outFile.close();
        
        LOG_INFO("File decrypted successfully: " + inputPath + " -> " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during file decryption: " + std::string(e.what()));
        return false;
    }
}

std::string MainWindow::generateRandomSalt() {
    unsigned char salt[16];
    if (RAND_bytes(salt, 16) != 1) {
        throw std::runtime_error("Failed to generate random salt");
    }
    return std::string(reinterpret_cast<char*>(salt), 16);
}

std::string MainWindow::deriveKey(const std::string& password, const std::string& salt) {
    unsigned char key[32]; // AES-256 requiere 32 bytes
    
    if (PKCS5_PBKDF2_HMAC(password.c_str(), password.length(),
                          reinterpret_cast<const unsigned char*>(salt.c_str()), salt.length(),
                          10000, EVP_sha256(), 32, key) != 1) {
        throw std::runtime_error("Failed to derive key");
    }
    
    return std::string(reinterpret_cast<char*>(key), 32);
}

std::string MainWindow::aesEncrypt(const std::string& plaintext, const std::string& password) {
    try {
        // Generar salt y IV
        std::string salt = generateRandomSalt();
        unsigned char iv[AES_BLOCK_SIZE];
        if (RAND_bytes(iv, AES_BLOCK_SIZE) != 1) {
            throw std::runtime_error("Failed to generate IV");
        }
        
        // Derivar clave
        std::string key = deriveKey(password, salt);
        
        // Configurar contexto de encriptación
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            throw std::runtime_error("Failed to create cipher context");
        }
        
        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_cbc(), NULL, 
                              reinterpret_cast<const unsigned char*>(key.c_str()), iv) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to initialize encryption");
        }
        
        // Encriptar
        int len;
        int ciphertext_len;
        std::vector<unsigned char> ciphertext(plaintext.length() + AES_BLOCK_SIZE);
        
        if (EVP_EncryptUpdate(ctx, ciphertext.data(), &len, 
                             reinterpret_cast<const unsigned char*>(plaintext.c_str()), plaintext.length()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to encrypt data");
        }
        ciphertext_len = len;
        
        if (EVP_EncryptFinal_ex(ctx, ciphertext.data() + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to finalize encryption");
        }
        ciphertext_len += len;
        
        EVP_CIPHER_CTX_free(ctx);
        
        // Codificar en Base64: salt + iv + ciphertext
        std::string combined;
        combined += salt;
        combined += std::string(reinterpret_cast<char*>(iv), AES_BLOCK_SIZE);
        combined += std::string(reinterpret_cast<char*>(ciphertext.data()), ciphertext_len);
        
        return base64_encode(combined);
        
    } catch (const std::exception& e) {
        LOG_ERROR("AES encryption error: " + std::string(e.what()));
        return "";
    }
}

std::string MainWindow::aesDecrypt(const std::string& ciphertext, const std::string& password) {
    try {
        // Decodificar Base64
        std::string combined = base64_decode(ciphertext);
        
        if (combined.length() < 32) { // salt(16) + iv(16) mínimo
            throw std::runtime_error("Invalid encrypted data");
        }
        
        // Extraer componentes
        std::string salt = combined.substr(0, 16);
        unsigned char iv[AES_BLOCK_SIZE];
        memcpy(iv, combined.c_str() + 16, AES_BLOCK_SIZE);
        std::string encrypted = combined.substr(32);
        
        // Derivar clave
        std::string key = deriveKey(password, salt);
        
        // Configurar contexto de desencriptación
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            throw std::runtime_error("Failed to create cipher context");
        }
        
        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), NULL, 
                              reinterpret_cast<const unsigned char*>(key.c_str()), iv) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to initialize decryption");
        }
        
        // Desencriptar
        int len;
        int plaintext_len;
        std::vector<unsigned char> plaintext(encrypted.length() + AES_BLOCK_SIZE);
        
        if (EVP_DecryptUpdate(ctx, plaintext.data(), &len, 
                             reinterpret_cast<const unsigned char*>(encrypted.c_str()), encrypted.length()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to decrypt data");
        }
        plaintext_len = len;
        
        if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to finalize decryption");
        }
        plaintext_len += len;
        
        EVP_CIPHER_CTX_free(ctx);
        
        return std::string(reinterpret_cast<char*>(plaintext.data()), plaintext_len);
        
    } catch (const std::exception& e) {
        LOG_ERROR("AES decryption error: " + std::string(e.what()));
        return "";
    }
}

// Funciones helper para descarga
bool MainWindow::downloadDirectFile(const FileInfo& fileInfo, const std::string& savePath, const std::string& decryptPassword) {
    try {
        wxString fileName = wxString::FromUTF8(fileInfo.fileName);
        wxString fullPath = wxString::FromUTF8(savePath) + wxFileName::GetPathSeparator() + fileName;
        
        bool success = m_telegramHandler->downloadFile(fileInfo.telegramFileId, fullPath.ToStdString());
        
        if (success && !decryptPassword.empty()) {
            // Desencriptar archivo descargado
            std::string tempPath = fullPath.ToStdString() + ".tmp";
            std::filesystem::rename(fullPath.ToStdString(), tempPath);
            
            if (decryptFile(tempPath, fullPath.ToStdString(), decryptPassword)) {
                std::filesystem::remove(tempPath);
                return true;
            } else {
                // Restaurar archivo original
                std::filesystem::rename(tempPath, fullPath.ToStdString());
                return false;
            }
        }
        
        return success;
    } catch (const std::exception& e) {
        LOG_ERROR("Error in downloadDirectFile: " + std::string(e.what()));
        return false;
    }
}

bool MainWindow::downloadChunkedFile(const std::string& fileId, const std::vector<ChunkInfo>& chunks, const std::string& savePath, const std::string& decryptPassword) {
    try {
        wxString fileName = wxString::FromUTF8(m_database->getFileInfo(fileId).fileName);
        wxString fullPath = wxString::FromUTF8(savePath) + wxFileName::GetPathSeparator() + fileName;
        
        // Crear directorio temporal
        std::string tempDir = "temp_download_" + fileId;
        std::filesystem::create_directories(tempDir);
        
        // Descargar chunks
        for (const auto& chunk : chunks) {
            std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
            
            if (!m_telegramHandler->downloadFile(chunk.telegramFileId, chunkPath)) {
                std::filesystem::remove_all(tempDir);
                return false;
            }
        }
        
        // Reconstruir archivo
        std::ofstream finalFile(fullPath.ToStdString(), std::ios::binary);
        if (!finalFile.is_open()) {
            std::filesystem::remove_all(tempDir);
            return false;
        }
        
        for (const auto& chunk : chunks) {
            std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
            std::ifstream chunkFile(chunkPath, std::ios::binary);
            if (chunkFile.is_open()) {
                finalFile << chunkFile.rdbuf();
                chunkFile.close();
            }
        }
        
        finalFile.close();
        
        // Limpiar archivos temporales
        std::filesystem::remove_all(tempDir);
        
        // Desencriptar si es necesario
        if (!decryptPassword.empty()) {
            std::string tempPath = fullPath.ToStdString() + ".tmp";
            std::filesystem::rename(fullPath.ToStdString(), tempPath);
            
            if (decryptFile(tempPath, fullPath.ToStdString(), decryptPassword)) {
                std::filesystem::remove(tempPath);
                return true;
            } else {
                std::filesystem::rename(tempPath, fullPath.ToStdString());
                return false;
            }
        }
        
        return true;
    } catch (const std::exception& e) {
        LOG_ERROR("Error in downloadChunkedFile: " + std::string(e.what()));
        return false;
    }
}

std::string MainWindow::detectMimeType(const std::string& fileName) {
    // Detectar tipo MIME basado en extensión
    std::string ext = fileName;
    size_t dotPos = ext.find_last_of('.');
    if (dotPos != std::string::npos) {
        ext = ext.substr(dotPos + 1);
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    }
    
    if (ext == "txt") return "text/plain";
    if (ext == "pdf") return "application/pdf";
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
    if (ext == "mp3") return "audio/mpeg";
    if (ext == "wav") return "audio/wav";
    if (ext == "mp4") return "video/mp4";
    if (ext == "avi") return "video/x-msvideo";
    if (ext == "zip") return "application/zip";
    if (ext == "rar") return "application/x-rar-compressed";
    if (ext == "7z") return "application/x-7z-compressed";
    if (ext == "exe") return "application/x-msdownload";
    if (ext == "dll") return "application/x-msdownload";
    
    return "application/octet-stream";
}

void MainWindow::OnSearch(wxCommandEvent& event) {
    wxString searchText = m_searchTextCtrl->GetValue();
    m_currentSearch = searchText.ToStdString();
    LoadFiles();
}

void MainWindow::OnSearchTextChanged(wxCommandEvent& event) {
    wxString searchText = m_searchTextCtrl->GetValue();
    m_currentSearch = searchText.ToStdString();
    LoadFiles();
}

void MainWindow::OnClearSearch(wxCommandEvent& event) {
    m_searchTextCtrl->Clear();
    m_currentSearch = "";
    LoadFiles();
}

void MainWindow::OnSortByName(wxCommandEvent& event) {
    m_currentSortBy = "name";
    LoadFiles();
}

void MainWindow::OnSortBySize(wxCommandEvent& event) {
    m_currentSortBy = "size";
    LoadFiles();
}

void MainWindow::OnSortByDate(wxCommandEvent& event) {
    m_currentSortBy = "date";
    LoadFiles();
}

void MainWindow::OnSortByType(wxCommandEvent& event) {
    m_currentSortBy = "type";
    LoadFiles();
}

void MainWindow::OnSortAscending(wxCommandEvent& event) {
    m_sortAscending = true;
    LoadFiles();
}

void MainWindow::OnSortDescending(wxCommandEvent& event) {
    m_sortAscending = false;
    LoadFiles();
}

void MainWindow::OnConfig(wxCommandEvent& event) {
    LOG_INFO("Opening configuration dialog");
    ShowConfigDialog();
}

void MainWindow::ShowConfigDialog() {
    // Implementar diálogo de configuración
    wxMessageBox("Configuration dialog not yet implemented.", "Configuration", wxOK | wxICON_INFORMATION);
}

void MainWindow::SetAppIcon() {
    // Implementar icono de aplicación
}

// Funciones helper para Base64
std::string base64_encode(const std::string& input) {
    static const std::string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    
    std::string ret;
    int val = 0, valb = -6;
    for (unsigned char c : input) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) {
            ret.push_back(chars[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) ret.push_back(chars[((val << 8) >> (valb + 8)) & 0x3F]);
    while (ret.size() % 4) ret.push_back('=');
    return ret;
}

std::string base64_decode(const std::string& input) {
    static const int T[256] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1
    };
    
    std::string ret;
    int val = 0, valb = -8;
    for (unsigned char c : input) {
        if (T[c] == -1) break;
        val = (val << 6) + T[c];
        valb += 6;
        if (valb >= 0) {
            ret.push_back(char((val >> valb) & 0xFF));
            valb -= 8;
        }
    }
    return ret;
}
                    m_uploadProgress->Hide();
                    
                    wxString msg = wxString::Format(
                        "No chunks found in database for this file.\n\n"
                        "File: %s\n\n"
                        "This may happen if:\n"
                        "- File was uploaded without chunk tracking\n"
                        "- Database was cleared\n\n"
                        "Re-upload the file to enable chunked download.",
                        fileName
                    );
                    
                    wxMessageBox(msg, "Download Error", wxOK | wxICON_INFORMATION);
                });
                return;
            }
            
            LOG_INFO("Found " + std::to_string(chunks.size()) + " chunks to download");
            
            // Actualizar progreso inicial
            int totalChunks = chunks.size();
            std::atomic<int> downloadedChunks(0);
            
            // Crear directorio temporal
            std::string tempDir = "temp_download_" + fileId;
            std::filesystem::create_directories(tempDir);
            
            // Descargar chunks en lotes paralelos (máximo 5 a la vez)
            const int MAX_CONCURRENT_DOWNLOADS = 5;
            bool allSuccess = true;
            
            for (size_t i = 0; i < chunks.size(); i += MAX_CONCURRENT_DOWNLOADS) {
                std::vector<std::future<bool>> futures;
                
                // Procesar lote actual
                size_t batchEnd = std::min(i + MAX_CONCURRENT_DOWNLOADS, chunks.size());
                
                for (size_t j = i; j < batchEnd; j++) {
                    const ChunkInfo& chunk = chunks[j];
                    
                    auto future = std::async(std::launch::async, [this, chunk, tempDir, &downloadedChunks, totalChunks]() {
                        std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
                        
                        // Reintentar hasta 3 veces en caso de timeout
                        bool success = false;
                        for (int retry = 0; retry < 3 && !success; retry++) {
                            if (retry > 0) {
                                LOG_WARNING("Retrying chunk " + std::to_string(chunk.chunkNumber) + " (attempt " + std::to_string(retry + 1) + "/3)");
                                std::this_thread::sleep_for(std::chrono::seconds(1));
                            }
                            success = m_telegramHandler->downloadFile(chunk.telegramFileId, chunkPath);
                        }
                        
                        if (success) {
                            downloadedChunks++;
                            
                            // Actualizar progreso
                            int completed = downloadedChunks.load();
                            double percent = (double)completed / totalChunks * 100.0;
                            
                            wxTheApp->CallAfter([this, completed, totalChunks, percent]() {
                                m_uploadProgress->SetValue((int)percent);
                                m_uploadStatusLabel->SetLabel(
                                    wxString::Format("Downloading: %d%%", (int)percent)
                                );
                                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                            });
                        }
                        
                        return success;
                    });
                    
                    futures.push_back(std::move(future));
                }
                
                // Esperar a que termine el lote actual
                for (auto& f : futures) {
                    if (!f.get()) {
                        allSuccess = false;
                    }
                }
                
                // Si falla un lote, abortar
                if (!allSuccess) {
                    break;
                }
            }
            
            if (!allSuccess) {
                LOG_ERROR("Failed to download all chunks");
                std::filesystem::remove_all(tempDir);
                
                wxTheApp->CallAfter([this]() {
                    m_uploadProgress->Hide();
                    wxMessageBox("Failed to download some chunks.\n\nCheck logs for details.",
                                "Download Error", wxOK | wxICON_ERROR);
                });
                return;
            }
            
            LOG_INFO("All chunks downloaded successfully. Reconstructing file...");
            
            // Reconstruir archivo
            std::ofstream finalFile(destPath.ToStdString(), std::ios::binary);
            if (!finalFile.is_open()) {
                LOG_ERROR("Failed to create final file: " + destPath.ToStdString());
                std::filesystem::remove_all(tempDir);
                
                wxTheApp->CallAfter([this]() {
                    m_uploadProgress->Hide();
                    wxMessageBox("Failed to create output file.",
                                "Download Error", wxOK | wxICON_ERROR);
                });
                return;
            }
            
            // Concatenar chunks en orden
            for (const ChunkInfo& chunk : chunks) {
                std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
                
                std::ifstream chunkFile(chunkPath, std::ios::binary);
                if (!chunkFile.is_open()) {
                    LOG_ERROR("Failed to open chunk file: " + chunkPath);
                    continue;
                }
                
                finalFile << chunkFile.rdbuf();
                chunkFile.close();
            }
            
            finalFile.close();
            
            // Limpiar archivos temporales
            std::filesystem::remove_all(tempDir);
            
            LOG_INFO("File reconstructed successfully: " + destPath.ToStdString());
            
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
            
            wxTheApp->CallAfter([this, fileName, destPath, totalChunks]() {
                m_uploadProgress->SetValue(100);
                m_uploadStatusLabel->SetLabel("Download completed");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                
                wxString msg = wxString::Format(
                    "Download Successful\n\n"
                    "File: %s\n"
                    "Location: %s\n"
                    "Chunks: %d\n\n"
                    "Check logs/telegram_cloud_*.txt for details.",
                    fileName, destPath, totalChunks
                );
                
                wxMessageBox(msg, "Download Completed", wxOK | wxICON_INFORMATION);
                
                wxSleep(2);
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
            });
            
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
            });
        }
        
        } catch (const std::exception& e) {
            LOG_CRITICAL("Exception in download thread: " + std::string(e.what()));
            
            wxTheApp->CallAfter([this, e]() {
                m_uploadProgress->Hide();
                m_uploadStatusLabel->SetLabel("Ready");
                
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
                wxMessageBox("Download crashed with unknown error.\n\nCheck logs for details.",
                            "Download Error", wxOK | wxICON_ERROR);
            });
        }
    });
    
    downloadThread.detach();
    LOG_INFO("Download started in background thread");
}

void MainWindow::OnDelete(wxCommandEvent& event) {
    // Verificar si hay archivos seleccionados con checkbox
    if (m_selectedItems.empty()) {
        wxMessageBox("Please select one or more files to delete",
                    "Delete", wxOK | wxICON_INFORMATION);
        return;
    }
    
    // Mensaje de confirmación
    int fileCount = m_selectedItems.size();
    wxString confirmMsg;
    if (fileCount == 1) {
        long index = *m_selectedItems.begin();
        wxString fileName = m_filesListCtrl->GetItemText(index, 1);
        confirmMsg = "Are you sure you want to delete '" + fileName + "'?\n\nThis will permanently remove the file from both Telegram and the local database.";
    } else {
        confirmMsg = wxString::Format("Are you sure you want to delete %d files?\n\nThis will permanently remove them from both Telegram and the local database.", fileCount);
    }
    
    int ret = wxMessageBox(confirmMsg, "Delete Files", wxYES_NO | wxICON_QUESTION);
    
    if (ret == wxYES) {
        // Recopilar todos los file IDs
        std::vector<std::pair<long, std::string>> filesToDelete;
        for (long index : m_selectedItems) {
            auto it = m_itemToFileId.find(index);
            if (it != m_itemToFileId.end()) {
                wxString fileName = m_filesListCtrl->GetItemText(index, 1);
                filesToDelete.push_back({index, it->second});
                LOG_INFO("Queued for deletion: " + fileName.ToStdString() + " (ID: " + it->second + ")");
            }
        }
        
        if (filesToDelete.empty()) {
            wxMessageBox("No valid files to delete.", "Delete Error", wxOK | wxICON_ERROR);
            return;
        }
        
        LOG_INFO("Starting batch delete operation for " + std::to_string(filesToDelete.size()) + " files");
        
        // Mostrar progreso
        m_uploadProgress->Show();
        m_uploadProgress->SetValue(0);
        m_uploadStatusLabel->SetLabel("Deleting file...");
        m_uploadStatusLabel->Show();
        
        // Ejecutar eliminación en hilo separado
        std::thread deleteThread([this, filesToDelete]() {
            try {
                int totalFiles = filesToDelete.size();
                int successfulDeletes = 0;
                int failedDeletes = 0;
                
                for (int i = 0; i < totalFiles; ++i) {
                    const auto& [index, fileId] = filesToDelete[i];
                    
                    // Actualizar progreso
                    int progress = (i * 100) / totalFiles;
                    wxTheApp->CallAfter([this, progress, i, totalFiles]() {
                        m_uploadProgress->SetValue(progress);
                        m_uploadStatusLabel->SetLabel(wxString::Format("Deleting %d/%d...", i + 1, totalFiles));
                    });
                    
                    bool fileSuccess = true;
                    
                    // 1. Obtener mensajes a eliminar de Telegram
                    auto messagesToDelete = m_database->getMessagesToDelete(fileId);
                    
                    LOG_INFO("Found " + std::to_string(messagesToDelete.size()) + " messages to delete for file: " + fileId);
                    
                    // 2. Eliminar mensajes de Telegram
                    for (const auto& msg : messagesToDelete) {
                        int64_t messageId = msg.first;
                        std::string botToken = msg.second;
                        
                        if (!m_telegramHandler->deleteMessage(messageId, botToken)) {
                            LOG_WARNING("Failed to delete message " + std::to_string(messageId) + " from Telegram");
                        }
                    }
                    
                    // 3. Eliminar de la base de datos
                    if (!m_database->deleteFile(fileId)) {
                        LOG_ERROR("Failed to delete file from database: " + fileId);
                        fileSuccess = false;
                        failedDeletes++;
                    } else {
                        successfulDeletes++;
                    }
                }
                
                // 4. Actualizar interfaz
                wxTheApp->CallAfter([this, successfulDeletes, failedDeletes, totalFiles]() {
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
                    if (failedDeletes == 0) {
                        msg = wxString::Format(
                            "Files deleted successfully!\n\n"
                            "Total: %d files deleted",
                            successfulDeletes
                        );
                        wxMessageBox(msg, "Delete Successful", wxOK | wxICON_INFORMATION);
                    } else {
                        msg = wxString::Format(
                            "Batch delete completed with errors\n\n"
                            "Successful: %d\n"
                            "Failed: %d\n\n"
                            "Check logs for details.",
                            successfulDeletes, failedDeletes
                        );
                        wxMessageBox(msg, "Delete Completed", wxOK | wxICON_WARNING);
                    }
                    
                    LOG_INFO("Batch delete completed: " + std::to_string(successfulDeletes) + " successful, " + std::to_string(failedDeletes) + " failed");
                });
                
            } catch (const std::exception& e) {
                LOG_CRITICAL("Exception in delete thread: " + std::string(e.what()));
                
                wxTheApp->CallAfter([this, e]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    m_selectedItems.clear();
                    
                    wxString msg = wxString::Format(
                        "Delete crashed with exception:\n\n%s\n\n"
                        "Check logs for details.",
                        e.what()
                    );
                    
                    wxMessageBox(msg, "Delete Error", wxOK | wxICON_ERROR);
                });
            } catch (...) {
                LOG_CRITICAL("Unknown exception in delete thread");
                
                wxTheApp->CallAfter([this]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->Hide();
                    m_uploadStatusLabel->SetLabel("Ready");
                    m_selectedItems.clear();
                    
                    wxMessageBox("Delete crashed with unknown error.\n\nCheck logs for details.",
                                "Delete Error", wxOK | wxICON_ERROR);
                });
            }
        });
        
        deleteThread.detach();
        LOG_INFO("Delete started in background thread");
    }
}

void MainWindow::OnTestConnection(wxCommandEvent& event) {
    LOG_INFO("Testing Telegram connection...");
    m_testConnectionButton->Enable(false);
    SetStatusText("Testing connection...");
    
    if (!m_telegramHandler) {
        LOG_ERROR("TelegramHandler not initialized");
        wxMessageBox("TelegramHandler not initialized", "Error", wxOK | wxICON_ERROR);
        m_testConnectionButton->Enable(true);
        return;
    }
    
    bool success = m_telegramHandler->testConnection();
    
    m_testConnectionButton->Enable(true);
    
    if (success) {
        m_serverStatusLabel->SetLabel("Server: Connected ✓");
        SetStatusText("Connection test: SUCCESS");
        wxMessageBox("Connection test successful!\n\nConnected to Telegram Bot API.\n\nCheck logs/telegram_cloud_*.txt for details.",
                    "Connection Test", wxOK | wxICON_INFORMATION);
    } else {
        m_serverStatusLabel->SetLabel("Server: Connection Failed");
        SetStatusText("Connection test: FAILED");
        wxMessageBox("Connection test failed!\n\nCheck your bot token and internet connection.\n\nSee logs/telegram_cloud_*.txt for details.",
                    "Connection Test", wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnQuit(wxCommandEvent& event) {
    Close(true);
}

void MainWindow::OnAbout(wxCommandEvent& event) {
    wxMessageBox("Telegram Cloud Desktop\nVersion 1.0.0\n\nBuilt with wxWidgets",
                "About", wxOK | wxICON_INFORMATION);
}

void MainWindow::OnDecryptFile(wxCommandEvent& event) {
    // Seleccionar archivo encriptado
    wxFileDialog openFileDialog(this, "Select encrypted file", "", "",
                               "All files (*.*)|*.*", wxFD_OPEN | wxFD_FILE_MUST_EXIST);
    
    if (openFileDialog.ShowModal() == wxID_CANCEL) {
        return;
    }
    
    wxString encryptedFilePath = openFileDialog.GetPath();
    
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
    wxString suggestedName = inputFile.GetName() + "_decrypted." + inputFile.GetExt();
    
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
        wxMessageBox("File decrypted successfully!\n\nSaved to: " + outputFilePath,
                    "Success", wxOK | wxICON_INFORMATION);
    } else {
        wxMessageBox("Failed to decrypt file.\n\nPossible reasons:\n- Wrong password\n- File is corrupted\n- File is not encrypted",
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


void MainWindow::LoadFiles() {
    m_filesListCtrl->DeleteAllItems();
    m_itemToFileId.clear(); // Limpiar mapeo anterior
    
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
        // Leer archivo encriptado
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) {
            LOG_ERROR("Failed to open input file for decryption: " + inputPath);
            return false;
        }
        
        std::vector<char> fileData((std::istreambuf_iterator<char>(inFile)),
                                    std::istreambuf_iterator<char>());
        inFile.close();
        
        // Convertir a string
        std::string ciphertext(fileData.begin(), fileData.end());
        
        // Desencriptar
        std::string decrypted = aesDecrypt(ciphertext, password);
        
        // Escribir archivo desencriptado
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to open output file for decryption: " + outputPath);
            return false;
        }
        
        outFile.write(decrypted.c_str(), decrypted.size());
        outFile.close();
        
        LOG_INFO("File decrypted successfully: " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("File decryption failed: " + std::string(e.what()));
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
    std::string key = deriveKey(password, salt);
    
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
    
    // Derivar clave
    std::string key = deriveKey(password, salt);
    
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
    long itemIndex = m_filesListCtrl->GetNextItem(-1, wxLIST_NEXT_ALL, wxLIST_STATE_SELECTED);
    if (itemIndex == -1) {
        wxMessageBox("Please select a file to share.", "No File Selected", wxOK | wxICON_WARNING);
        return;
    }
    
    auto it = m_itemToFileId.find(itemIndex);
    if (it == m_itemToFileId.end()) {
        wxMessageBox("Error: File ID not found.", "Error", wxOK | wxICON_ERROR);
        return;
    }
    
    std::string fileId = it->second;
    
    // Get user password
    wxString password = wxGetPasswordFromUser(
        "Enter a password to encrypt the share link:",
        "Share File",
        "",
        this
    );
    
    if (password.IsEmpty()) {
        return; // User cancelled
    }
    
    // Get file info from database
    FileInfo fileInfo = m_database->getFileInfo(fileId);
    std::vector<ChunkInfo> chunks = m_database->getFileChunks(fileId);
    
    // Create share data structure with encryption status
    std::string shareData;
    std::string encryptedFlag = fileInfo.isEncrypted ? "true" : "false";
    
    if (!chunks.empty()) {
        // Chunked file
        shareData = "{\"type\":\"chunked\",\"file_id\":\"" + fileId + "\",\"encrypted\":" + encryptedFlag + "}";
    } else if (fileInfo.fileId == fileId) {
        // Direct file
        shareData = "{\"type\":\"direct\",\"file_id\":\"" + fileId + "\",\"encrypted\":" + encryptedFlag + "}";
    } else {
        wxMessageBox("Error: File not found in database.", "Error", wxOK | wxICON_ERROR);
        return;
    }
    
    // Encrypt with user password using AES-256
    std::string userPassword = std::string(password.mb_str());
    try {
        std::string encrypted = aesEncrypt(shareData, userPassword);
        std::string encoded = base64Encode(encrypted);
    
    // Show share dialog
    wxDialog shareDialog(this, wxID_ANY, "Share File", wxDefaultPosition, wxSize(500, 300));
    wxBoxSizer* mainSizer = new wxBoxSizer(wxVERTICAL);
    
    wxStaticText* infoLabel = new wxStaticText(&shareDialog, wxID_ANY, 
        "Share this encrypted link with others:");
    mainSizer->Add(infoLabel, 0, wxALL, 10);
    
    wxTextCtrl* linkText = new wxTextCtrl(&shareDialog, wxID_ANY, wxString(encoded),
        wxDefaultPosition, wxDefaultSize, wxTE_MULTILINE | wxTE_READONLY);
    linkText->SetFont(wxFont(8, wxFONTFAMILY_TELETYPE, wxFONTSTYLE_NORMAL, wxFONTWEIGHT_NORMAL));
    mainSizer->Add(linkText, 1, wxEXPAND | wxLEFT | wxRIGHT, 10);
    
    wxBoxSizer* buttonSizer = new wxBoxSizer(wxHORIZONTAL);
    wxButton* copyButton = new wxButton(&shareDialog, wxID_ANY, "Copy to Clipboard");
    wxButton* closeButton = new wxButton(&shareDialog, wxID_CANCEL, "Close");
    
    buttonSizer->Add(copyButton, 0, wxALL, 5);
    buttonSizer->Add(closeButton, 0, wxALL, 5);
    mainSizer->Add(buttonSizer, 0, wxALIGN_CENTER | wxALL, 10);
    
    shareDialog.SetSizer(mainSizer);
    
    // Copy button handler
    copyButton->Bind(wxEVT_BUTTON, [&shareDialog, encoded](wxCommandEvent&) {
        if (wxTheClipboard->Open()) {
            wxTheClipboard->SetData(new wxTextDataObject(encoded));
            wxTheClipboard->Close();
            wxMessageBox("Link copied to clipboard!", "Success", wxOK | wxICON_INFORMATION);
        }
    });
    
    shareDialog.ShowModal();
    } catch (const std::exception& e) {
        wxMessageBox("Failed to encrypt share link: " + std::string(e.what()), 
                    "Encryption Error", wxOK | wxICON_ERROR);
    }
}

void MainWindow::OnDownloadFromLink(wxCommandEvent& event) {
    wxString encryptedLink = wxGetTextFromUser(
        "Enter the encrypted share link:",
        "Download from Link",
        "",
        this
    );
    
    if (encryptedLink.IsEmpty()) {
        return; // User cancelled
    }
    
    // Get user password
    wxString password = wxGetPasswordFromUser(
        "Enter the password to decrypt the link:",
        "Decrypt Link",
        "",
        this
    );
    
    if (password.IsEmpty()) {
        return; // User cancelled
    }
    
    try {
        // Decode and decrypt
        std::string encoded = std::string(encryptedLink.mb_str());
        std::string encrypted = base64Decode(encoded);
        std::string userPassword = std::string(password.mb_str());
        std::string decrypted = aesDecrypt(encrypted, userPassword);
        
        // Parse JSON (simple parsing for our format)
        size_t typePos = decrypted.find("\"type\":\"");
        size_t fileIdPos = decrypted.find("\"file_id\":\"");
        
        if (typePos == std::string::npos || fileIdPos == std::string::npos) {
            wxMessageBox("Invalid share link format.", "Error", wxOK | wxICON_ERROR);
            return;
        }
        
        typePos += 8; // Skip "type":""
        size_t typeEnd = decrypted.find("\"", typePos);
        std::string fileType = decrypted.substr(typePos, typeEnd - typePos);
        
        fileIdPos += 11; // Skip "file_id":""
        size_t fileIdEnd = decrypted.find("\"", fileIdPos);
        std::string fileId = decrypted.substr(fileIdPos, fileIdEnd - fileIdPos);
        
        // Check if file is encrypted
        bool isFileEncrypted = false;
        size_t encryptedPos = decrypted.find("\"encrypted\":");
        if (encryptedPos != std::string::npos) {
            encryptedPos += 12; // Skip "encrypted":"
            size_t encryptedEnd = decrypted.find_first_of(",}", encryptedPos);
            std::string encryptedValue = decrypted.substr(encryptedPos, encryptedEnd - encryptedPos);
            isFileEncrypted = (encryptedValue == "true");
        }
        
        // If file is encrypted, ask for decryption password
        std::string filePassword;
        if (isFileEncrypted) {
            wxString filePwd = wxGetPasswordFromUser(
                "This file is encrypted. Enter the file decryption password:",
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
        
        // Show progress
        m_uploadProgress->Show();
        m_uploadProgress->SetValue(0);
        m_uploadStatusLabel->SetLabel("Downloading from link...");
        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
        
        // Start download in background thread
        std::thread downloadThread([this, fileId, fileType, saveDir, isFileEncrypted, filePassword]() {
            try {
                bool success = false;
                
                if (fileType == "chunked") {
                    // Download chunked file
                    auto chunks = m_database->getFileChunks(fileId);
                    if (!chunks.empty()) {
                        success = downloadChunkedFile(fileId, chunks, std::string(saveDir.mb_str()), filePassword);
                    }
                } else if (fileType == "direct") {
                    // Download direct file
                    FileInfo fileInfo = m_database->getFileInfo(fileId);
                    if (fileInfo.fileId == fileId) {
                        success = downloadDirectFile(fileInfo, std::string(saveDir.mb_str()), filePassword);
                    }
                }
                
                // Update UI in main thread
                wxTheApp->CallAfter([this, success]() {
                    m_uploadProgress->Hide();
                    if (success) {
                        m_uploadStatusLabel->SetLabel("Download completed");
                        m_uploadStatusLabel->SetForegroundColour(wxColour(100, 100, 100));
                        wxMessageBox("File downloaded successfully!", "Success", wxOK | wxICON_INFORMATION);
                    } else {
                        m_uploadStatusLabel->SetLabel("Download failed");
                        m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                        wxMessageBox("Failed to download file.", "Error", wxOK | wxICON_ERROR);
                    }
                });
                
            } catch (const std::exception& e) {
                wxTheApp->CallAfter([this, e]() {
                    m_uploadProgress->Hide();
                    m_uploadStatusLabel->SetLabel("Download failed");
                    m_uploadStatusLabel->SetForegroundColour(wxColour(220, 53, 69));
                    wxMessageBox("Error downloading file: " + std::string(e.what()), "Error", wxOK | wxICON_ERROR);
                });
            }
        });
        
        downloadThread.detach();
        
        LOG_INFO("Share link processed - File ID: " + fileId + ", Type: " + fileType + " - Download started");
        
    } catch (const std::exception& e) {
        std::string errorMsg = std::string(e.what());
        if (errorMsg.find("wrong password") != std::string::npos) {
            wxMessageBox("Wrong password. Please check the password and try again.",
                        "Decryption Failed", wxOK | wxICON_ERROR);
        } else {
            wxMessageBox("Error processing share link: " + errorMsg, 
                    "Error", wxOK | wxICON_ERROR);
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
    wxDialog configDialog(this, wxID_ANY, "Configuration", wxDefaultPosition, wxSize(600, 500));
    wxBoxSizer* mainSizer = new wxBoxSizer(wxVERTICAL);
    
    // Load current configuration
    std::string botToken = "";
    std::string apiId = "";
    std::string apiHash = "";
    std::string channelId = "";
    std::string additionalBotTokens = "";
    
    // Try to read from .env file
    std::ifstream envFile(".env");
    if (envFile.is_open()) {
        std::string line;
        while (std::getline(envFile, line)) {
            size_t pos = line.find('=');
            if (pos != std::string::npos) {
                std::string key = line.substr(0, pos);
                std::string value = line.substr(pos + 1);
                
                if (key == "BOT_TOKEN") botToken = value;
                else if (key == "API_ID") apiId = value;
                else if (key == "API_HASH") apiHash = value;
                else if (key == "CHANNEL_ID") channelId = value;
                else if (key == "ADDITIONAL_BOT_TOKENS") additionalBotTokens = value;
            }
        }
        envFile.close();
    }
    
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
        
        // Save to .env file
        std::ofstream envFile(".env");
        if (envFile.is_open()) {
            envFile << "BOT_TOKEN=" << newBotToken << "\n";
            envFile << "API_ID=" << newApiId << "\n";
            envFile << "API_HASH=" << newApiHash << "\n";
            envFile << "CHANNEL_ID=" << newChannelId << "\n";
            if (!newAdditionalTokens.empty()) {
                envFile << "ADDITIONAL_BOT_TOKENS=" << newAdditionalTokens << "\n";
            }
            envFile.close();
            
            wxMessageBox("Configuration saved successfully!\n\nPlease restart the application to load the new configuration.", 
                        "Success", wxOK | wxICON_INFORMATION);
            configDialog.EndModal(wxID_OK);
        } else {
            wxMessageBox("Error saving configuration file.", "Error", wxOK | wxICON_ERROR);
        }
    });
    
    configDialog.ShowModal();
}

void MainWindow::ShowConfigDialog() {
    LOG_INFO("Showing configuration dialog for first-time setup");
    OnConfig(wxCommandEvent());
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

} // namespace TelegramCloud
