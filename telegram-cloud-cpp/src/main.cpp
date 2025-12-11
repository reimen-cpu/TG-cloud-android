#include <wx/wx.h>
#include "mainwindow.h"
#include "config.h"
#include "logger.h"
#include "anti_debug.h"
#include <iostream>

using namespace TelegramCloud;

class TelegramCloudApp : public wxApp {
public:
    virtual bool OnInit() override;
};

wxIMPLEMENT_APP(TelegramCloudApp);

bool TelegramCloudApp::OnInit() {
    // Anti-Debug Check al iniciar aplicación
    ANTI_DEBUG_CHECK();
    
    // Set application info
    SetAppName("TelegramCloud");
    SetVendorName("TelegramCloud");
    
    // Application icon will be set in MainWindow constructor
    
    
    // Inicializar logger
    LOG_INFO("=============================================================");
    LOG_INFO("Telegram Cloud Desktop Application (wxWidgets)");
    LOG_INFO("Version: 1.0.0");
    LOG_INFO("=============================================================");
    
    // Verify configuration
    Config& config = Config::instance();
    bool configValid = config.isValid();
    
    if (!configValid) {
        std::string error = config.validationError();
        LOG_WARNING("Configuration not found or invalid: " + error);
        LOG_INFO("Application will start in limited mode (download from links only)");
    }
    
    if (configValid) {
        LOG_INFO("Configuration validated successfully");
        LOG_INFO("Bot tokens: " + std::to_string(config.allTokens().size()));
        LOG_INFO("Channel ID: " + config.channelId());
    }
    
    // Create and show main window
    LOG_INFO("Creating main window...");
    MainWindow* mainWindow = new MainWindow(configValid);
    mainWindow->Show(true);
    
    LOG_INFO("Application started successfully");
    LOG_INFO("Main window displayed");
    
    return true;
}
