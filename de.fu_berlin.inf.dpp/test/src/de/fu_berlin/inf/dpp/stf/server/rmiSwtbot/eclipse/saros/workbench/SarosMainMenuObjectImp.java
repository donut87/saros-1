package de.fu_berlin.inf.dpp.stf.server.rmiSwtbot.eclipse.saros.workbench;

import java.rmi.RemoteException;

import de.fu_berlin.inf.dpp.net.JID;
import de.fu_berlin.inf.dpp.stf.server.rmiSwtbot.eclipse.workbench.EclipseMainMenuObjectImp;

public class SarosMainMenuObjectImp extends EclipseMainMenuObjectImp implements
    SarosMainMenuObject {
    // public static SarosMainMenuObjectImp classVariable;

    private static transient SarosMainMenuObjectImp self;

    /**
     * {@link SarosMainMenuObjectImp} is a singleton, but inheritance is
     * possible.
     */
    public static SarosMainMenuObjectImp getInstance() {
        if (self != null)
            return self;
        self = new SarosMainMenuObjectImp();
        return self;
    }

    public void creatNewAccount(JID jid, String password)
        throws RemoteException {
        workbenchObject.getEclipseShell().activate().setFocus();
        menuObject.clickMenuWithTexts("Saros", "Create Account");
        exportedWindowObject.confirmCreateNewUserAccountWindow(jid.getDomain(),
            jid.getName(), password);
    }
}
