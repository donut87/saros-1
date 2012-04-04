package de.fu_berlin.inf.dpp.project.internal;

import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;

import de.fu_berlin.inf.dpp.User;
import de.fu_berlin.inf.dpp.activities.business.AbstractActivityReceiver;
import de.fu_berlin.inf.dpp.activities.business.IActivity;
import de.fu_berlin.inf.dpp.activities.business.StartFollowingActivity;
import de.fu_berlin.inf.dpp.activities.business.StopFollowingActivity;
import de.fu_berlin.inf.dpp.annotations.Component;
import de.fu_berlin.inf.dpp.awareness.AwarenessInformationCollector;
import de.fu_berlin.inf.dpp.editor.AbstractSharedEditorListener;
import de.fu_berlin.inf.dpp.editor.EditorManager;
import de.fu_berlin.inf.dpp.project.AbstractActivityProvider;
import de.fu_berlin.inf.dpp.project.AbstractSarosSessionListener;
import de.fu_berlin.inf.dpp.project.ISarosSession;
import de.fu_berlin.inf.dpp.project.ISarosSessionListener;
import de.fu_berlin.inf.dpp.project.SarosSessionManager;

/**
 * This manager is responsible for distributing knowledge about changes in
 * follow modes between session participants
 * 
 * @author Alexander Waldmann (contact@net-corps.de)
 */
@Component(module = "core")
public class FollowingActivitiesManager extends AbstractActivityProvider {

    private static final Logger log = Logger
        .getLogger(FollowingActivitiesManager.class);

    protected final List<IFollowModeChangesListener> internalListeners = new LinkedList<IFollowModeChangesListener>();
    protected SarosSessionManager sessionManager;
    protected ISarosSession sarosSession;
    protected EditorManager editorManager;
    protected AwarenessInformationCollector awarenessInformationCollector;

    public FollowingActivitiesManager(SarosSessionManager sessionManager,
        EditorManager editorManager,
        AwarenessInformationCollector awarenessInformationCollector) {
        this.sessionManager = sessionManager;
        this.editorManager = editorManager;
        this.awarenessInformationCollector = awarenessInformationCollector;
        sessionManager.addSarosSessionListener(sessionListener);
        editorManager
            .addSharedEditorListener(new AbstractSharedEditorListener() {
                @Override
                public void followModeChanged(User target, boolean isFollowed) {
                    if (sarosSession == null) {
                        log.error("FollowModeChanged Event listener got a call without a running session.");
                        return;
                    }
                    for (User user : sarosSession.getParticipants()) {
                        if (isFollowed) {
                            sarosSession.sendActivity(
                                user,
                                new StartFollowingActivity(sarosSession
                                    .getLocalUser(), target));
                        } else {
                            sarosSession.sendActivity(
                                user,
                                new StopFollowingActivity(sarosSession
                                    .getLocalUser()));

                        }
                    }
                }
            });
    }

    @Override
    public void exec(IActivity activity) {
        activity.dispatch(receiver);
    }

    protected AbstractActivityReceiver receiver = new AbstractActivityReceiver() {
        @Override
        public void receive(StartFollowingActivity activity) {
            User user = activity.getSource();
            if (!user.isInSarosSession()) {
                throw new IllegalArgumentException(MessageFormat.format(
                    "illegal follow mode activity received", user));
            }

            log.info("Received new follow mode from: "
                + user.getHumanReadableName() + " target: "
                + activity.getTarget().getHumanReadableName());

            awarenessInformationCollector.setUserFollowing(user,
                activity.getTarget());
            notifyListeners();
        }

        @Override
        public void receive(StopFollowingActivity activity) {
            User user = activity.getSource();
            if (!user.isInSarosSession()) {
                throw new IllegalArgumentException(MessageFormat.format(
                    "illegal follow mode activity received", user));
            }

            log.info("User " + user.getHumanReadableName()
                + " stopped follow mode");

            awarenessInformationCollector.setUserFollowing(user, null);
            notifyListeners();
        }
    };

    protected ISarosSessionListener sessionListener = new AbstractSarosSessionListener() {
        @Override
        public void sessionStarted(ISarosSession session) {
            sarosSession = session;
            awarenessInformationCollector.flushFollowModes();
            session.addActivityProvider(FollowingActivitiesManager.this);
        }

        @Override
        public void sessionEnded(ISarosSession session) {
            awarenessInformationCollector.flushFollowModes();
            session.removeActivityProvider(FollowingActivitiesManager.this);
            sarosSession = null;
        }
    };

    public void notifyListeners() {
        for (IFollowModeChangesListener listener : this.internalListeners) {
            listener.followModeChanged();
        }
    }

    public void addIinternalListener(IFollowModeChangesListener listener) {
        this.internalListeners.add(listener);
    }

    public void removeIinternalListener(IFollowModeChangesListener listener) {
        this.internalListeners.remove(listener);
    }

}