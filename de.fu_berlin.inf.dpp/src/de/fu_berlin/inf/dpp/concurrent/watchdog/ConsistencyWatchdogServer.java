package de.fu_berlin.inf.dpp.concurrent.watchdog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.picocontainer.annotations.Inject;

import de.fu_berlin.inf.dpp.Saros;
import de.fu_berlin.inf.dpp.Saros.ConnectionState;
import de.fu_berlin.inf.dpp.concurrent.management.DocumentChecksum;
import de.fu_berlin.inf.dpp.editor.EditorManager;
import de.fu_berlin.inf.dpp.project.AbstractSessionListener;
import de.fu_berlin.inf.dpp.project.ISharedProject;
import de.fu_berlin.inf.dpp.project.SessionManager;

/**
 * This class is an eclipse job run on the host side ONLY.
 * 
 * The job computes checksums for all files currently managed by Jupiter (the
 * ConcurrentDocumentManager) and sends them to all guests.
 * 
 * These will call their ConcurrentDocumentManager.check(...) method, to verify
 * that their version is correct.
 * 
 * Once started with schedule() the job is scheduled to rerun every INTERVAL ms.
 * 
 * @author chjacob
 * 
 *         TODO Make ConsistencyWatchDog configurable => Timeout, Whether run or
 *         not, etc.
 * 
 * @component The single instance of this class per application is created by
 *            PicoContainer in the central plug-in class {@link Saros}
 * 
 */
public class ConsistencyWatchdogServer extends Job {

    private static Logger log = Logger
        .getLogger(ConsistencyWatchdogServer.class);

    protected static final long INTERVAL = 10000;

    // this map holds for all open editors of all participants the checksums
    protected final HashMap<IPath, DocumentChecksum> docsChecksums = new HashMap<IPath, DocumentChecksum>();

    protected Set<IDocument> registeredDocuments = new HashSet<IDocument>();

    protected Set<IDocument> dirtyDocument = new HashSet<IDocument>();

    protected IDocumentListener dirtyListener = new IDocumentListener() {

        public void documentAboutToBeChanged(DocumentEvent event) {
            // we are only interested in events after the change
        }

        public void documentChanged(DocumentEvent event) {
            dirtyDocument.add(event.getDocument());
        }
    };

    @Inject
    protected Saros saros;

    @Inject
    protected EditorManager editorManager;

    protected SessionManager sessionManager;

    protected ISharedProject sharedProject;

    public ConsistencyWatchdogServer(SessionManager sessionManager) {
        super("ConsistencyWatchdog");

        this.sessionManager = sessionManager;

        this.sessionManager.addSessionListener(new AbstractSessionListener() {
            @Override
            public void sessionStarted(ISharedProject newSharedProject) {

                if (newSharedProject.isHost()) {
                    sharedProject = newSharedProject;
                    log.debug("Starting consistency watchdog");
                    setSystem(true);
                    setPriority(Job.SHORT);
                    schedule();
                }
            }

            @Override
            public void sessionEnded(ISharedProject session) {

                if (sharedProject != null) {
                    // Cancel Job
                    cancel();
                    sharedProject = null;

                    // Unregister from all documents
                    for (IDocument document : registeredDocuments) {
                        document.removeDocumentListener(dirtyListener);
                    }
                    registeredDocuments.clear();

                    // Reset all dirty states
                    dirtyDocument.clear();
                }
            }
        });
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {

        if (sessionManager.getSharedProject() == null)
            return Status.OK_STATUS;

        assert sharedProject.isHost() : "This job is intended to be run on host side!";

        // If connection is closed, checking does not make sense...
        if (saros.getConnectionState() != ConnectionState.CONNECTED) {
            // Reschedule the next run in 30 seconds
            schedule(30000);
            return Status.OK_STATUS;
        }

        Set<IDocument> missingDocuments = new HashSet<IDocument>(
            registeredDocuments);

        // Update Checksums for all documents controlled by jupiter
        for (IPath docPath : editorManager.getOpenEditorsOfAllParticipants()) {

            if (monitor.isCanceled())
                return Status.CANCEL_STATUS;

            // Get document
            IDocument doc = editorManager.getDocument(docPath);

            // TODO CO Handle missing files correctly
            if (doc == null) {
                log.error("Can't get Document");
                docsChecksums.remove(docPath);
                continue;
            }

            // Update listener management
            missingDocuments.remove(doc);
            if (!registeredDocuments.contains(doc)) {
                registeredDocuments.add(doc);
                doc.addDocumentListener(dirtyListener);
                dirtyDocument.add(doc);
            }

            // If document not changed, skip
            if (!dirtyDocument.contains(doc))
                continue;

            // If no entry for this document exists create a new one
            if (docsChecksums.get(docPath) == null) {
                DocumentChecksum c = new DocumentChecksum(docPath, doc
                    .getLength(), doc.get().hashCode());
                docsChecksums.put(docPath, c);
            } else {
                // else set new length and hash
                DocumentChecksum c = docsChecksums.get(docPath);
                c.setLength(doc.getLength());
                c.setHash(doc.get().hashCode());
            }
        }

        // Reset dirty states
        dirtyDocument.clear();

        // Unregister all documents that are no longer there
        for (IDocument missing : missingDocuments) {
            registeredDocuments.remove(missing);
            missing.removeDocumentListener(dirtyListener);
        }

        // Send to all Clients
        if (docsChecksums.values().size() > 0) {
            // TODO Connection of Transmitter might be closed at the moment
            sessionManager.getTransmitter().sendDocChecksumsToClients(
                docsChecksums.values());
        }

        // Reschedule the next run in INTERVAL ms
        schedule(INTERVAL);
        return Status.OK_STATUS;
    }
}
