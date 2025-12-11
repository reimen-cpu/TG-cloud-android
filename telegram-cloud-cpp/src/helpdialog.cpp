#include "helpdialog.h"

namespace TelegramCloud {

wxBEGIN_EVENT_TABLE(HelpDialog, wxDialog)
    EVT_BUTTON(ID_HELP_UPLOAD, HelpDialog::OnSectionUpload)
    EVT_BUTTON(ID_HELP_DOWNLOAD, HelpDialog::OnSectionDownload)
    EVT_BUTTON(ID_HELP_SHARE, HelpDialog::OnSectionShare)
    EVT_BUTTON(ID_HELP_BACKUPS, HelpDialog::OnSectionBackups)
    EVT_BUTTON(ID_HELP_SEARCH_SORT, HelpDialog::OnSectionSearchSort)
    EVT_BUTTON(ID_HELP_CONFIG, HelpDialog::OnSectionConfig)
    EVT_BUTTON(ID_HELP_UNIVERSAL_LINKS, HelpDialog::OnSectionUniversalLinks)
    EVT_BUTTON(ID_HELP_DOWNLOAD_FROM_LINK, HelpDialog::OnSectionDownloadFromLink)
    EVT_BUTTON(ID_HELP_SECURITY, HelpDialog::OnSectionSecurity)
    EVT_BUTTON(ID_HELP_NOTIFICATIONS, HelpDialog::OnSectionNotifications)
    EVT_BUTTON(ID_HELP_CLOSE, HelpDialog::OnClose)
wxEND_EVENT_TABLE()

HelpDialog::HelpDialog(wxWindow* parent)
    : wxDialog(parent, wxID_ANY, "User Guide", wxDefaultPosition, wxSize(1100, 720), wxDEFAULT_DIALOG_STYLE | wxRESIZE_BORDER) {
    SetBackgroundColour(wxColour(45, 45, 45));

    wxBoxSizer* rootSizer = new wxBoxSizer(wxHORIZONTAL);

    // Lateral de secciones
    wxBoxSizer* leftSizer = new wxBoxSizer(wxVERTICAL);

    auto mkBtn = [&](int id, const wxString& label) {
        wxButton* b = new wxButton(this, id, label, wxDefaultPosition, wxSize(220, 34));
        b->SetBackgroundColour(wxColour(60, 60, 60));
        b->SetForegroundColour(*wxWHITE);
        leftSizer->Add(b, 0, wxALL, 4);
    };

    mkBtn(ID_HELP_UPLOAD, "Upload files");
    mkBtn(ID_HELP_DOWNLOAD, "Downloads");
    mkBtn(ID_HELP_SHARE, "Share links");
    mkBtn(ID_HELP_BACKUPS, "Backups");
    mkBtn(ID_HELP_SEARCH_SORT, "Search and sorting");
    mkBtn(ID_HELP_CONFIG, "Settings");
    mkBtn(ID_HELP_UNIVERSAL_LINKS, "Universal links");
    mkBtn(ID_HELP_DOWNLOAD_FROM_LINK, "Download by link");
    mkBtn(ID_HELP_SECURITY, "Safety");
    mkBtn(ID_HELP_NOTIFICATIONS, "Notifications");

    wxButton* closeButton = new wxButton(this, ID_HELP_CLOSE, "Close", wxDefaultPosition, wxSize(220, 34));
    closeButton->SetBackgroundColour(wxColour(80, 50, 50));
    closeButton->SetForegroundColour(*wxWHITE);
    leftSizer->Add(closeButton, 0, wxALL, 8);

    // Contenido
    wxBoxSizer* rightSizer = new wxBoxSizer(wxVERTICAL);
    m_content = new wxTextCtrl(this, wxID_ANY, "", wxDefaultPosition, wxDefaultSize,
                               wxTE_MULTILINE | wxTE_READONLY | wxTE_RICH2);
    m_content->SetBackgroundColour(wxColour(35, 35, 35));
    m_content->SetForegroundColour(*wxWHITE);
    rightSizer->Add(m_content, 1, wxEXPAND | wxALL, 8);

    rootSizer->Add(leftSizer, 0, wxEXPAND | wxALL, 8);
    rootSizer->Add(rightSizer, 1, wxEXPAND | wxTOP | wxRIGHT | wxBOTTOM, 8);

    SetSizerAndFit(rootSizer);
    SetMinSize(wxSize(1000, 640));

    OnSectionUpload(*(new wxCommandEvent()));
}

void HelpDialog::setContent(const wxString& text) {
    m_content->ChangeValue(text);
}

void HelpDialog::OnSectionUpload(wxCommandEvent&) {
    setContent(
        "Upload files\n\n"
        "- Click 'Select File' or 'Multiple Files'.\n"
        "- Optional: check 'Encrypt files' to protect your files.\n"
        "- Use the buttons to Pause, Resume, Stop, or Cancel.\n"
        "- When it finishes, the file appears in the list."
    );
}

void HelpDialog::OnSectionDownload(wxCommandEvent&) {
    setContent(
        "Downloads\n\n"
        "- Select one or more items in the list.\n"
        "- Click 'Download' and choose a folder.\n"
        "- If a password is needed, the app will ask for it.\n"
        "- Wait until it completes before closing the app."
    );
}

void HelpDialog::OnSectionShare(wxCommandEvent&) {
    setContent(
        "Share links\n\n"
        "- Click 'Share' to create a link for a file.\n"
        "- Optional: add a password before creating the link.\n"
        "- Copy the link and share it with others.\n"
        "- The link can open directly in the app."
    );
}

void HelpDialog::OnSectionBackups(wxCommandEvent&) {
    setContent(
        "Backups\n\n"
        "- Click 'Backup' to save your data in a single file.\n"
        "- Click 'Restore' to recover a previous backup.\n"
        "- Keep backups in a safe place."
    );
}

void HelpDialog::OnSectionSearchSort(wxCommandEvent&) {
    setContent(
        "Search and sorting\n\n"
        "- Use the search box to find files by name.\n"
        "- Click 'Clear' to remove the search.\n"
        "- In the 'View' menu, choose how to sort files.\n"
        "- Switch between ascending and descending order."
    );
}

void HelpDialog::OnSectionConfig(wxCommandEvent&) {
    setContent(
        "Settings\n\n"
        "- Open 'Config' -> 'Configuration...'.\n"
        "- Fill the fields with your information.\n"
        "- Save changes. Restart the app if asked."
    );
}

void HelpDialog::OnSectionUniversalLinks(wxCommandEvent&) {
    setContent(
        "Universal links\n\n"
        "- These links open the app and go straight to the file.\n"
        "- Create them with 'Share' and copy the link.\n"
        "- Keep the link private if it contains sensitive files."
    );
}

void HelpDialog::OnSectionDownloadFromLink(wxCommandEvent&) {
    setContent(
        "Download by link\n\n"
        "- Click 'Download from Link' and paste a link.\n"
        "- If a password is needed, enter it when asked.\n"
        "- Choose a folder and wait until it finishes."
    );
}

void HelpDialog::OnSectionSecurity(wxCommandEvent&) {
    setContent(
        "Safety\n\n"
        "- Protect your account and device with a strong password.\n"
        "- Do not share links or passwords in public places.\n"
        "- Keep your app updated when new versions are available.\n"
        "- Store backups in locations you trust."
    );
}

void HelpDialog::OnSectionNotifications(wxCommandEvent&) {
    setContent(
        "Notifications\n\n"
        "- You can receive alerts in Telegram about your files.\n"
        "- Start the bot shown in the app and follow the steps.\n"
        "- You will get messages when uploads or downloads finish.\n"
        "- You can tap links in the message to open the file in the app.\n"
        "- Send '%' in the Telegram chat to get the current progress.\n"
        "- To pause or stop alerts, use the options in Settings."
    );
}

void HelpDialog::OnClose(wxCommandEvent&) {
    EndModal(wxID_OK);
}

} // namespace TelegramCloud


