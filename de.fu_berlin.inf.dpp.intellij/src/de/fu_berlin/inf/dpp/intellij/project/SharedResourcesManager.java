package de.fu_berlin.inf.dpp.intellij.project;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import de.fu_berlin.inf.dpp.activities.FileActivity;
import de.fu_berlin.inf.dpp.activities.FolderCreatedActivity;
import de.fu_berlin.inf.dpp.activities.FolderDeletedActivity;
import de.fu_berlin.inf.dpp.activities.IActivity;
import de.fu_berlin.inf.dpp.activities.IFileSystemModificationActivity;
import de.fu_berlin.inf.dpp.activities.SPath;
import de.fu_berlin.inf.dpp.filesystem.IFile;
import de.fu_berlin.inf.dpp.filesystem.IFolder;
import de.fu_berlin.inf.dpp.intellij.editor.EditorManager;
import de.fu_berlin.inf.dpp.intellij.editor.LocalEditorHandler;
import de.fu_berlin.inf.dpp.intellij.editor.LocalEditorManipulator;
import de.fu_berlin.inf.dpp.intellij.editor.SelectedEditorState;
import de.fu_berlin.inf.dpp.intellij.editor.annotations.AnnotationManager;
import de.fu_berlin.inf.dpp.intellij.project.filesystem.IntelliJWorkspaceImpl;
import de.fu_berlin.inf.dpp.observables.FileReplacementInProgressObservable;
import de.fu_berlin.inf.dpp.session.AbstractActivityConsumer;
import de.fu_berlin.inf.dpp.session.AbstractActivityProducer;
import de.fu_berlin.inf.dpp.session.IActivityConsumer;
import de.fu_berlin.inf.dpp.session.IActivityConsumer.Priority;
import de.fu_berlin.inf.dpp.session.ISarosSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.picocontainer.Startable;

/** The SharedResourcesManager creates and handles file and folder activities. */
public class SharedResourcesManager extends AbstractActivityProducer implements Startable {

  private static final Logger LOG = Logger.getLogger(SharedResourcesManager.class);

  private static final int DELETION_FLAGS = 0;
  private static final boolean FORCE = false;
  private static final boolean LOCAL = false;

  private final ISarosSession sarosSession;

  private final FileSystemChangeListener fileSystemListener;

  /**
   * Should return <code>true</code> while executing resource changes to avoid an infinite resource
   * event loop.
   */
  private final FileReplacementInProgressObservable fileReplacementInProgressObservable;

  private final LocalEditorHandler localEditorHandler;

  private final LocalEditorManipulator localEditorManipulator;

  private final AnnotationManager annotationManager;

  @Override
  public void start() {
    ApplicationManager.getApplication()
        .invokeAndWait(
            () -> {
              sarosSession.addActivityProducer(SharedResourcesManager.this);
              sarosSession.addActivityConsumer(consumer, Priority.ACTIVE);
              fileSystemListener.setEnabled(true);
            },
            ModalityState.defaultModalityState());
  }

  @Override
  public void stop() {
    ApplicationManager.getApplication()
        .invokeAndWait(
            () -> {
              fileSystemListener.setEnabled(false);
              sarosSession.removeActivityProducer(SharedResourcesManager.this);
              sarosSession.removeActivityConsumer(consumer);
            },
            ModalityState.defaultModalityState());
  }

  public SharedResourcesManager(
      ISarosSession sarosSession,
      EditorManager editorManager,
      FileReplacementInProgressObservable fileReplacementInProgressObservable,
      LocalEditorHandler localEditorHandler,
      LocalEditorManipulator localEditorManipulator,
      IntelliJWorkspaceImpl intelliJWorkspaceImpl,
      AnnotationManager annotationManager) {

    this.sarosSession = sarosSession;
    this.fileReplacementInProgressObservable = fileReplacementInProgressObservable;
    this.localEditorHandler = localEditorHandler;
    this.localEditorManipulator = localEditorManipulator;
    fileSystemListener =
        new FileSystemChangeListener(this, editorManager, intelliJWorkspaceImpl, sarosSession);
    this.annotationManager = annotationManager;
  }

  private final IActivityConsumer consumer =
      new AbstractActivityConsumer() {
        @Override
        public void exec(IActivity activity) {
          if (!(activity instanceof IFileSystemModificationActivity)) {
            return;
          }

          LOG.trace("executing " + activity + " in " + Thread.currentThread().getName());

          super.exec(activity);

          LOG.trace("done executing " + activity);
        }

        @Override
        public void receive(FileActivity activity) {
          try {
            handleFileActivity(activity);
          } catch (IOException e) {
            LOG.error("Failed to execute activity: " + activity, e);
          }
        }

        @Override
        public void receive(FolderCreatedActivity activity) {
          try {
            handleFolderCreation(activity);
          } catch (IOException e) {
            LOG.error("Failed to execute activity: " + activity, e);
          }
        }

        @Override
        public void receive(FolderDeletedActivity activity) {
          try {
            handleFolderDeletion(activity);
          } catch (IOException e) {
            LOG.error("Failed to execute activity: " + activity, e);
          }
        }
      };

  private void handleFileActivity(@NotNull FileActivity activity) throws IOException {

    if (activity.isRecovery()) {
      handleFileRecovery(activity);
      return;
    }

    switch (activity.getType()) {
      case CREATED:
        handleFileCreation(activity);
        break;
      case REMOVED:
        handleFileDeletion(activity);
        break;
      case MOVED:
        handleFileMove(activity);
        break;
      default:
        throw new UnsupportedOperationException(
            "FileActivity type "
                + activity.getType()
                + " not supported. Dropped activity: "
                + activity);
    }
  }

  private void handleFileRecovery(@NotNull FileActivity activity) throws IOException {

    SPath path = activity.getPath();

    LOG.debug("performing recovery for file: " + activity.getPath().getFullPath());

    FileActivity.Type type = activity.getType();

    try {
      if (type == FileActivity.Type.CREATED) {
        // TODO handle case if file already exists and only content needs to be recovered
        handleFileCreation(activity);
      } else if (type == FileActivity.Type.REMOVED) {
        handleFileDeletion(activity);
      } else {
        LOG.warn("performing recovery for type " + type + " is not supported");
      }
    } finally {
      /*
       * always reset Jupiter or we will get into trouble because the
       * vector time has already been reset on the host
       */
      sarosSession.getConcurrentDocumentClient().reset(path);
    }
  }

  /**
   * Applies the given move FileActivity. Subsequently cleans up the EditorPool and
   * AnnotationManager for the moved file if necessary.
   *
   * @param activity the move activity to execute
   * @throws IOException if the creation of the new file or the deletion of the old file fails
   */
  private void handleFileMove(@NotNull FileActivity activity) throws IOException {

    SPath oldPath = activity.getOldPath();
    SPath newPath = activity.getPath();

    IFile oldFile = oldPath.getFile();
    IFile newFile = newPath.getFile();

    if (!oldFile.exists()) {
      LOG.warn(
          "Could not move file "
              + oldFile
              + " as it does not exist."
              + " source: "
              + oldFile
              + " destination: "
              + newFile);

      return;
    }

    boolean fileOpen = localEditorHandler.isOpenEditor(oldPath);

    SelectedEditorState selectedEditorState = null;

    if (fileOpen) {
      selectedEditorState = new SelectedEditorState();
      selectedEditorState.captureState();
    }

    localEditorManipulator.closeEditor(oldPath);

    annotationManager.updateAnnotationPath(oldFile, newFile);

    try {
      fileSystemListener.setEnabled(false);

      localEditorHandler.saveDocument(oldPath);

      newFile.create(oldFile.getContents(), FORCE);

      if (fileOpen) {
        localEditorManipulator.openEditor(newPath, false);

        try {
          selectedEditorState.replaceSelectedFile(oldFile, newFile);
        } catch (IllegalStateException e) {
          LOG.warn("Failed to update the captured selected editor state", e);
        }

        selectedEditorState.applyCapturedState();
      }

      oldFile.delete(DELETION_FLAGS);

    } finally {
      fileSystemListener.setEnabled(true);
    }

    // TODO reset the vector time for the old file
  }

  private void handleFileDeletion(@NotNull FileActivity activity) throws IOException {

    SPath path = activity.getPath();
    IFile file = path.getFile();

    if (!file.exists()) {
      LOG.warn("Could not delete file " + file + " as it does not exist.");

      return;
    }

    if (localEditorHandler.isOpenEditor(path)) {
      localEditorManipulator.closeEditor(path);
    }

    try {
      fileSystemListener.setEnabled(false);

      localEditorHandler.saveDocument(path);

      file.delete(DELETION_FLAGS);

    } finally {
      fileSystemListener.setEnabled(true);
    }

    annotationManager.removeAnnotations(file);

    // TODO reset the vector time for the deleted file
  }

  private void handleFileCreation(@NotNull FileActivity activity) throws IOException {

    SPath path = activity.getPath();
    IFile file = path.getFile();

    if (file.exists()) {
      LOG.warn("Could not create file " + file + " as it already exists.");

      return;
    }

    InputStream contents = new ByteArrayInputStream(activity.getContent());

    try {
      fileSystemListener.setEnabled(false);

      file.create(contents, FORCE);

    } finally {
      fileSystemListener.setEnabled(true);
    }
  }

  private void handleFolderCreation(@NotNull FolderCreatedActivity activity) throws IOException {

    IFolder folder = activity.getPath().getFolder();

    if (folder.exists()) {
      LOG.warn("Could not create folder " + folder + " as it already exist.");

      return;
    }

    try {
      fileSystemListener.setEnabled(false);

      folder.create(FORCE, LOCAL);

    } finally {
      fileSystemListener.setEnabled(true);
    }
  }

  /**
   * Applies the given FolderDeletedActivity.
   *
   * <p><b>NOTE:</b> This currently does not check whether the deleted folder contains resources
   * outside the session scope. As a result, submodules of the shared module that are not present
   * for a different participant can be deleted accidentally through remote activities.
   *
   * @param activity the FolderDeletedActivity to execute
   * @throws IOException if the folder deletion fails
   */
  // TODO deal with children that are not part of the current session (submodules)
  private void handleFolderDeletion(@NotNull FolderDeletedActivity activity) throws IOException {

    IFolder folder = activity.getPath().getFolder();

    if (!folder.exists()) {
      LOG.warn("Could not delete folder " + folder + " as it does not exist.");

      return;
    }

    try {
      fileSystemListener.setEnabled(false);

      folder.delete(DELETION_FLAGS);

    } finally {
      fileSystemListener.setEnabled(true);
    }
  }

  void internalFireActivity(IActivity activity) {
    // HACK for now
    if (fileReplacementInProgressObservable.isReplacementInProgress()) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("File replacement in progress - Ignoring local activity " + activity);
      }

      return;
    }

    fireActivity(activity);
  }
}
