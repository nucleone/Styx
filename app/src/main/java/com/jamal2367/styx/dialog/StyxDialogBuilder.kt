package com.jamal2367.styx.dialog

import com.jamal2367.styx.MainActivity
import com.jamal2367.styx.R
import com.jamal2367.styx.constant.HTTP
import com.jamal2367.styx.controller.UIController
import com.jamal2367.styx.database.Bookmark
import com.jamal2367.styx.database.asFolder
import com.jamal2367.styx.database.bookmark.BookmarkRepository
import com.jamal2367.styx.database.downloads.DownloadsRepository
import com.jamal2367.styx.database.history.HistoryRepository
import com.jamal2367.styx.di.DatabaseScheduler
import com.jamal2367.styx.di.MainScheduler
import com.jamal2367.styx.download.DownloadHandler
import com.jamal2367.styx.extensions.copyToClipboard
import com.jamal2367.styx.extensions.resizeAndShow
import com.jamal2367.styx.html.bookmark.BookmarkPageFactory
import com.jamal2367.styx.preference.UserPreferences
import com.jamal2367.styx.utils.IntentUtils
import com.jamal2367.styx.utils.isBookmarkUrl
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipboardManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.core.net.toUri
import com.anthonycr.grant.PermissionsManager
import com.anthonycr.grant.PermissionsResultAction
import com.jamal2367.styx.extensions.snackbar
import dagger.Reusable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.subscribeBy
import javax.inject.Inject

/**
 * A builder of various dialogs.
 */
@Reusable
class StyxDialogBuilder @Inject constructor(
    private val bookmarkManager: BookmarkRepository,
    private val downloadsModel: DownloadsRepository,
    private val historyModel: HistoryRepository,
    private val userPreferences: UserPreferences,
    private val downloadHandler: DownloadHandler,
    private val clipboardManager: ClipboardManager,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    enum class NewTab {
        FOREGROUND,
        BACKGROUND,
        INCOGNITO
    }

    /**
     * Show the appropriated dialog for the long pressed link. It means that we try to understand
     * if the link is relative to a bookmark or is just a folder.
     *
     * @param activity used to show the dialog
     * @param url      the long pressed url
     */
    fun showLongPressedDialogForBookmarkUrl(
            activity: AppCompatActivity,
            uiController: UIController,
            url: String
    ) {
        if (url.isBookmarkUrl()) {
            // TODO hacky, make a better bookmark mechanism in the future
            val uri = url.toUri()
            val filename = requireNotNull(uri.lastPathSegment) { "Last segment should always exist for bookmark file" }
            val folderTitle = filename.substring(0, filename.length - BookmarkPageFactory.FILENAME.length - 1)
            showBookmarkFolderLongPressedDialog(activity, uiController, folderTitle.asFolder())
        } else {
            bookmarkManager.findBookmarkForUrl(url)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe { historyItem ->
                    // TODO: 6/14/17 figure out solution to case where slashes get appended to root urls causing the item to not exist
                    showLongPressedDialogForBookmarkUrl(activity, uiController, historyItem)
                }
        }
    }

    fun showLongPressedDialogForBookmarkUrl(
            activity: AppCompatActivity,
            uiController: UIController,
            entry: Bookmark.Entry
    ) = BrowserDialog.show(activity, R.string.action_bookmarks,
        DialogItem(title = R.string.dialog_open_new_tab) {
            uiController.handleNewTab(NewTab.FOREGROUND, entry.url)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            uiController.handleNewTab(NewTab.BACKGROUND, entry.url)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is MainActivity
        ) {
            uiController.handleNewTab(NewTab.INCOGNITO, entry.url)
        },
        DialogItem(title = R.string.action_share) {
            IntentUtils(activity).shareUrl(entry.url, entry.title)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            clipboardManager.copyToClipboard(entry.url)
            (activity).snackbar(R.string.message_link_copied)
        },
        DialogItem(title = R.string.dialog_remove_bookmark) {
            bookmarkManager.deleteBookmark(entry)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe { success ->
                    if (success) {
                        uiController.handleBookmarkDeleted(entry)
                        (activity).snackbar(R.string.action_remove_bookmark)
                    }
                }
        },
        DialogItem(title = R.string.dialog_edit_bookmark) {
            showEditBookmarkDialog(activity, uiController, entry)
        })

    /**
     * Show the appropriated dialog for the long pressed link.
     *
     * @param activity used to show the dialog
     */
    // TODO allow individual downloads to be deleted.
    fun showLongPressedDialogForDownloadUrl(
            activity: AppCompatActivity,
            uiController: UIController,
            url: String
    ) = BrowserDialog.show(activity, R.string.action_downloads,
        DialogItem(title = R.string.dialog_delete_all_downloads) {
            downloadsModel.deleteAllDownloads()
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe(uiController::handleDownloadDeleted)
        })

    /**
     * Show the add bookmark dialog. Shows a dialog with the title and URL pre-populated.
     */
    fun showAddBookmarkDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            entry: Bookmark.Entry
    ) {
        val editBookmarkDialog = MaterialAlertDialogBuilder(activity)
        editBookmarkDialog.setTitle(R.string.action_add_bookmark)
        val dialogLayout = View.inflate(activity, R.layout.dialog_edit_bookmark, null)
        val getTitle = dialogLayout.findViewById<EditText>(R.id.bookmark_title)
        getTitle.setText(entry.title)
        val getUrl = dialogLayout.findViewById<EditText>(R.id.bookmark_url)
        getUrl.setText(entry.url)
        val getFolder = dialogLayout.findViewById<AutoCompleteTextView>(R.id.bookmark_folder)
        getFolder.setHint(R.string.folder)
        getFolder.setText(entry.folder.title)

        val ignored = bookmarkManager.getFolderNames()
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { folders ->
                val suggestionsAdapter = ArrayAdapter(activity,
                    android.R.layout.simple_dropdown_item_1line, folders)
                getFolder.threshold = 1
                getFolder.setAdapter(suggestionsAdapter)
                editBookmarkDialog.setView(dialogLayout)
                editBookmarkDialog.setPositiveButton(activity.getString(R.string.action_ok)) { _, _ ->
                    val editedItem = Bookmark.Entry(
                        title = getTitle.text.toString(),
                        url = getUrl.text.toString(),
                        folder = getFolder.text.toString().asFolder(),
                        position = entry.position
                    )
                    bookmarkManager.addBookmarkIfNotExists(editedItem)
                        .subscribeOn(databaseScheduler)
                        .observeOn(mainScheduler)
                        .subscribeBy(
                            onSuccess = {
                                uiController.handleBookmarksChange()
                                (activity).snackbar(R.string.message_bookmark_added)
                            }
                        )
                }
                editBookmarkDialog.setNegativeButton(R.string.action_cancel) { _, _ -> }
                editBookmarkDialog.resizeAndShow()
            }
    }

    private fun showEditBookmarkDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            entry: Bookmark.Entry
    ) {
        val editBookmarkDialog = MaterialAlertDialogBuilder(activity)
        editBookmarkDialog.setTitle(R.string.title_edit_bookmark)
        val dialogLayout = View.inflate(activity, R.layout.dialog_edit_bookmark, null)
        val getTitle = dialogLayout.findViewById<EditText>(R.id.bookmark_title)
        getTitle.setText(entry.title)
        val getUrl = dialogLayout.findViewById<EditText>(R.id.bookmark_url)
        getUrl.setText(entry.url)
        val getFolder = dialogLayout.findViewById<AutoCompleteTextView>(R.id.bookmark_folder)
        getFolder.setHint(R.string.folder)
        getFolder.setText(entry.folder.title)

        bookmarkManager.getFolderNames()
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { folders ->
                val suggestionsAdapter = ArrayAdapter(activity,
                    android.R.layout.simple_dropdown_item_1line, folders)
                getFolder.threshold = 1
                getFolder.setAdapter(suggestionsAdapter)
                editBookmarkDialog.setView(dialogLayout)
                editBookmarkDialog.setPositiveButton(activity.getString(R.string.action_ok)) { _, _ ->
                    val editedItem = Bookmark.Entry(
                        title = getTitle.text.toString(),
                        url = getUrl.text.toString(),
                        folder = getFolder.text.toString().asFolder(),
                        position = entry.position
                    )
                    bookmarkManager.editBookmark(entry, editedItem)
                        .subscribeOn(databaseScheduler)
                        .observeOn(mainScheduler)
                        .subscribe(uiController::handleBookmarksChange)
                    (activity).snackbar(R.string.action_bookmark_edited)
                }
                editBookmarkDialog.resizeAndShow()
            }
    }

    fun showBookmarkFolderLongPressedDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            folder: Bookmark.Folder
    ) = BrowserDialog.show(activity, R.string.action_folder,
        DialogItem(title = R.string.dialog_rename_folder) {
            showRenameFolderDialog(activity, uiController, folder)
        },
        DialogItem(title = R.string.dialog_remove_folder) {
            bookmarkManager.deleteFolder(folder.title)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe {
                    uiController.handleBookmarkDeleted(folder)
                }
        })

    private fun showRenameFolderDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            folder: Bookmark.Folder
    ) = BrowserDialog.showEditText(activity,
        R.string.title_rename_folder,
        R.string.hint_title,
        folder.title,
        R.string.action_ok) { text ->
        if (text.isNotBlank()) {
            val oldTitle = folder.title
            bookmarkManager.renameFolder(oldTitle, text)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe(uiController::handleBookmarksChange)
        }
    }

    fun showLongPressedHistoryLinkDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            url: String
    ) = BrowserDialog.show(activity, R.string.action_history,
        DialogItem(title = R.string.dialog_open_new_tab) {
            uiController.handleNewTab(NewTab.FOREGROUND, url)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            uiController.handleNewTab(NewTab.BACKGROUND, url)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is MainActivity
        ) {
            uiController.handleNewTab(NewTab.INCOGNITO, url)
        },
        DialogItem(title = R.string.action_share) {
            IntentUtils(activity).shareUrl(url, null)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            clipboardManager.copyToClipboard(url)
            (activity).snackbar(R.string.message_link_copied)
        },
        DialogItem(title = R.string.dialog_remove_from_history) {
            historyModel.deleteHistoryEntry(url)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe(uiController::handleHistoryChange)
            (activity).snackbar(R.string.dialog_removed_from_history)
        })

    // TODO There should be a way in which we do not need an activity reference to dowload a file
    fun showLongPressImageDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            url: String,
            userAgent: String
    ) = BrowserDialog.show(activity, url.replace(HTTP, ""),
        DialogItem(title = R.string.dialog_open_new_tab) {
            uiController.handleNewTab(NewTab.FOREGROUND, url)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            uiController.handleNewTab(NewTab.BACKGROUND, url)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is MainActivity
        ) {
            uiController.handleNewTab(NewTab.INCOGNITO, url)
        },
        DialogItem(title = R.string.action_share) {
            IntentUtils(activity).shareUrl(url, null)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            clipboardManager.copyToClipboard(url)
            (activity).snackbar(R.string.message_link_copied)
        },
            DialogItem(title = R.string.dialog_download_image) {
                // Ask for required permissions before starting our download
                PermissionsManager.getInstance().requestPermissionsIfNecessaryForResult(activity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        object : PermissionsResultAction() {
                            override fun onGranted() {
                                downloadHandler.onDownloadStart(activity, userPreferences, url, userAgent, "attachment", null, "")
                            }
                            override fun onDenied(permission: String) {
                                //TODO show message
                            }
                        })
            })

    fun showLongPressLinkDialog(
            activity: AppCompatActivity,
            uiController: UIController,
            url: String,
            text: String?,
    ) = BrowserDialog.show(activity, url,
            DialogItem(title = R.string.dialog_open_new_tab) {
                uiController.handleNewTab(NewTab.FOREGROUND, url)
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                uiController.handleNewTab(NewTab.BACKGROUND, url)
            },
            DialogItem(
                    title = R.string.dialog_open_incognito_tab,
                    isConditionMet = activity is MainActivity
            ) {
                uiController.handleNewTab(NewTab.INCOGNITO, url)
            },
            DialogItem(title = R.string.action_share) {
                IntentUtils(activity).shareUrl(url, null)
            },
            DialogItem(title = R.string.dialog_copy_link) {
                clipboardManager.copyToClipboard(url)
                (activity).snackbar(R.string.message_link_copied)
            },
            // Show copy text dialog item if we have some text
            DialogItem(title = R.string.dialog_copy_text, isConditionMet = !text.isNullOrEmpty()) {
                if (!text.isNullOrEmpty()) clipboardManager.copyToClipboard(text)
            }
    )
}
