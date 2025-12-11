#ifndef HELPDIALOG_H
#define HELPDIALOG_H

#include <wx/wx.h>
#include <wx/sizer.h>
#include <wx/textctrl.h>

namespace TelegramCloud {

class HelpDialog : public wxDialog {
public:
    explicit HelpDialog(wxWindow* parent);

private:
    wxTextCtrl* m_content;

    void setContent(const wxString& text);

    void OnSectionUpload(wxCommandEvent&);
    void OnSectionDownload(wxCommandEvent&);
    void OnSectionShare(wxCommandEvent&);
    void OnSectionBackups(wxCommandEvent&);
    void OnSectionSearchSort(wxCommandEvent&);
    void OnSectionConfig(wxCommandEvent&);
    void OnSectionUniversalLinks(wxCommandEvent&);
    void OnSectionDownloadFromLink(wxCommandEvent&);
    void OnSectionSecurity(wxCommandEvent&);
    void OnSectionNotifications(wxCommandEvent&);
    void OnClose(wxCommandEvent&);

    wxDECLARE_EVENT_TABLE();
};

enum {
    ID_HELP_UPLOAD = wxID_HIGHEST + 500,
    ID_HELP_DOWNLOAD,
    ID_HELP_SHARE,
    ID_HELP_BACKUPS,
    ID_HELP_SEARCH_SORT,
    ID_HELP_CONFIG,
    ID_HELP_UNIVERSAL_LINKS,
    ID_HELP_DOWNLOAD_FROM_LINK,
    ID_HELP_SECURITY,
    ID_HELP_NOTIFICATIONS,
    ID_HELP_CLOSE
};

} // namespace TelegramCloud

#endif // HELPDIALOG_H


