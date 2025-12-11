#ifndef MAINWINDOW_H
#define MAINWINDOW_H

#include <wx/wx.h>
#include <wx/listctrl.h>
#include <wx/timer.h>
#include <wx/gauge.h>
#include <wx/hyperlink.h>
#include <memory>
#include <map>
#include <string>
#include <vector>
#include <chrono>
#include <thread>
#include "database.h"
#include "batchoperations.h"

namespace TelegramCloud {

class Database;
class TelegramHandler;
class TelegramNotifier;
class BatchOperations;

/**
 * @brief Ventana principal con wxWidgets
 */
class MainWindow : public wxFrame {
public:
    MainWindow(bool configValid = true);
    virtual ~MainWindow();
    
private:
    // Event handlers
    void OnUploadFile(wxCommandEvent& event);
    void OnUploadMultiple(wxCommandEvent& event);
    void OnRefresh(wxCommandEvent& event);
    void OnDownload(wxCommandEvent& event);
    void OnDelete(wxCommandEvent& event);
    void OnContactButton(wxCommandEvent& event);
    void OnCommunityButton(wxCommandEvent& event);
    void OnShare(wxCommandEvent& event);
    void OnDownloadFromLink(wxCommandEvent& event);
    void OnSearch(wxCommandEvent& event);
    void OnSearchTextChanged(wxCommandEvent& event);
    void OnClearSearch(wxCommandEvent& event);
    
    // Upload control handlers
    void OnPauseUpload(wxCommandEvent& event);
    void OnResumeUpload(wxCommandEvent& event);
    void OnStopUpload(wxCommandEvent& event);
    void OnCancelUpload(wxCommandEvent& event);
    void OnShowIncompleteUploads(wxCommandEvent& event);
    
    // Batch operations event handlers
    void OnListItemActivated(wxListEvent& event);
    void OnListItemClick(wxListEvent& event);
    
    void OnSortByName(wxCommandEvent& event);
    void OnSortBySize(wxCommandEvent& event);
    void OnSortByDate(wxCommandEvent& event);
    void OnSortByType(wxCommandEvent& event);
    void OnSortAscending(wxCommandEvent& event);
    void OnSortDescending(wxCommandEvent& event);
    void OnConfig(wxCommandEvent& event);
    void OnQuit(wxCommandEvent& event);
    void OnAbout(wxCommandEvent& event);
    void OnHelpGuide(wxCommandEvent& event);
    void OnDecryptFile(wxCommandEvent& event);
    void OnContactValidationTimer(wxTimerEvent& event);
    void SetAppIcon();
    
public:
    void ShowConfigDialog();
    void OnAnimationTimer(wxTimerEvent& event);
    
    // Métodos de encriptación AES-256 (para share links)
    std::string aesEncrypt(const std::string& plaintext, const std::string& password);
    std::string aesDecrypt(const std::string& ciphertext, const std::string& password);
    std::string generateRandomSalt();
    std::string deriveKey(const std::string& password, const std::string& salt);
    
    // Métodos de encriptación de archivos completos
    bool encryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password);
    bool decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password);
    
    // Protección de enlace de contacto
    void validateContactLink();
    std::string calculateContactChecksum();
    void terminateApplication();
    
    // UI setup
    void CreateMenuBar();
    void CreateControls();
    
    // Helper methods
    void LoadFiles();
    void UpdateStats();
    bool InitializeComponents();
    bool downloadDirectFile(const FileInfo& fileInfo, const std::string& saveDir, const std::string& decryptPassword = "");
    bool downloadChunkedFile(const std::string& fileId, const std::vector<ChunkInfo>& chunks, const std::string& saveDir, const std::string& decryptPassword = "");
    std::string detectMimeType(const wxString& filePath);
    
    // UI Components
    wxPanel* m_mainPanel;
    
    // Upload section
    wxButton* m_uploadButton;
    wxButton* m_uploadMultipleButton;
    wxCheckBox* m_encryptFilesCheckBox;
    wxGauge* m_uploadProgress;
    wxStaticText* m_uploadStatusLabel;
    
    // Upload control buttons
    wxButton* m_pauseButton;
    wxButton* m_resumeButton;
    wxButton* m_stopButton;
    wxButton* m_cancelButton;
    wxButton* m_showIncompleteButton;
    
    // Search section
    wxTextCtrl* m_searchTextCtrl;
    wxButton* m_searchButton;
    wxButton* m_clearSearchButton;
    
    // Files section
    wxListCtrl* m_filesListCtrl;
    wxButton* m_refreshButton;
    wxButton* m_downloadButton;
    wxButton* m_deleteButton;
    wxButton* m_shareButton;
    wxButton* m_downloadFromLinkButton;
    
    // Stats section
    wxStaticText* m_totalFilesLabel;
    wxStaticText* m_totalStorageLabel;
    wxStaticText* m_serverStatusLabel;
    wxButton* m_contactButton;
    wxButton* m_communityButton;
    wxHyperlinkCtrl* m_contactLink;
    
    // Backend components
    std::unique_ptr<Database> m_database;
    std::unique_ptr<TelegramHandler> m_telegramHandler;
    std::unique_ptr<TelegramNotifier> m_telegramNotifier;
    std::unique_ptr<BatchOperations> m_batchOperations;
    
    // Mapeo de índices de lista a file_id
    std::map<long, std::string> m_itemToFileId;
    
    // Selected items for batch operations
    std::set<long> m_selectedItems;
    
    // Search and sort state
    std::string m_currentSearch;
    std::string m_currentSortBy;
    bool m_sortAscending;
    
    // Configuration state
    bool m_configValid;
    
    // Variables para animación de puntos
    int m_dotAnimationCounter;
    bool m_showDotAnimation;
    wxTimer* m_animationTimer;
    wxTimer* m_contactValidationTimer;
    
    // Operation control state
    enum class OperationType {
        NONE,
        UPLOAD,
        DOWNLOAD,
        DOWNLOAD_LINK
    };
    
    OperationType m_currentOperationType;
    std::string m_currentUploadId;
    std::string m_currentDownloadId;
    std::thread m_uploadThread;
    
    // Helper methods for operation management
    void CheckIncompleteUploadsOnStartup();
    void CheckIncompleteDownloadsOnStartup();
    void OnShowIncompleteDownloads(wxCommandEvent& event);
    void OnCreateBackup(wxCommandEvent& event);
    void OnRestoreBackup(wxCommandEvent& event);
    void UpdateOperationControls(bool active, OperationType type = OperationType::NONE);
    bool ShowConfigurationWizard(bool prefillFromEnv = true);
    
    
    wxDECLARE_EVENT_TABLE();
};

// Event IDs
enum {
    ID_UPLOAD_FILE = wxID_HIGHEST + 1,
    ID_UPLOAD_MULTIPLE,
    ID_REFRESH,
    ID_DOWNLOAD,
    ID_DELETE,
    ID_SHARE,
    ID_DOWNLOAD_FROM_LINK,
    ID_SEARCH,
    ID_SEARCH_TEXT,
    ID_CLEAR_SEARCH,
    ID_SORT_BY_NAME,
    ID_SORT_BY_SIZE,
    ID_SORT_BY_DATE,
    ID_SORT_BY_TYPE,
    ID_SORT_ASCENDING,
    ID_SORT_DESCENDING,
    ID_CONFIG,
    ID_ANIMATION_TIMER,
    ID_CONTACT_VALIDATION_TIMER,
    ID_CONTACT_BUTTON,
    ID_COMMUNITY_BUTTON,
    ID_DECRYPT_FILE,
    ID_PAUSE_UPLOAD,
    ID_RESUME_UPLOAD,
    ID_STOP_UPLOAD,
    ID_CANCEL_UPLOAD,
    ID_SHOW_INCOMPLETE,
    ID_CREATE_BACKUP,
    ID_RESTORE_BACKUP,
    ID_HELP_GUIDE
};

} // namespace TelegramCloud

#endif // MAINWINDOW_H
