package de.fu_berlin.inf.dpp.stf.server.rmiSarosSWTBot.eclipse.workbench.finder.remoteWidgets;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTree;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;

import de.fu_berlin.inf.dpp.stf.server.rmiSarosSWTBot.eclipse.workbench.sarosFinder.remoteComponents.EclipseComponentImp;
import de.fu_berlin.inf.dpp.stf.server.sarosSWTBot.widgets.ContextMenuHelper;

public class STFTreeItemImp extends EclipseComponentImp implements STFTreeItem {
    private static transient STFTreeItemImp self;

    private SWTBotTreeItem swtBotTreeItem;

    public SWTBotTreeItem getSwtBotTreeItem() throws RemoteException {
        return swtBotTreeItem;
    }

    private SWTBotTree swtBotTree;

    /**
     * {@link STFTableImp} is a singleton, but inheritance is possible.
     */
    public static STFTreeItemImp getInstance() {
        if (self != null)
            return self;
        self = new STFTreeItemImp();
        return self;
    }

    public void setSWTBotTreeItem(SWTBotTreeItem item) throws RemoteException {
        this.swtBotTreeItem = item;
    }

    public void setSWTBotTree(SWTBotTree tree) throws RemoteException {
        this.swtBotTree = tree;
    }

    public STFMenu contextMenu(String text) throws RemoteException {
        STFMenuImp menu = STFMenuImp.getInstance();
        menu.setWidget(swtBotTreeItem.contextMenu(text));
        return menu;
    }

    public STFMenu contextMenu(String... texts) throws RemoteException {
        STFMenuImp menu = STFMenuImp.getInstance();
        menu.setWidget(ContextMenuHelper.getContextMenu(swtBotTree, texts));
        return menu;
    }

    public List<String> getSubItems() throws RemoteException {
        List<String> allItemTexts = new ArrayList<String>();
        for (SWTBotTreeItem item : swtBotTreeItem.getItems()) {
            allItemTexts.add(item.getText());
            log.info("existed subTreeItem of the TreeItem "
                + swtBotTreeItem.getText() + ": " + item.getText());
        }
        return allItemTexts;
    }

    public boolean existsSubItem(String text) throws RemoteException {
        return getSubItems().contains(text);
    }

    public boolean existsSubItemWithRegex(String regex) throws RemoteException {
        for (String itemText : getSubItems()) {
            if (itemText.matches(regex))
                return true;
        }
        return false;
    }

    public void waitUntilSubItemExists(final String subItemText)
        throws RemoteException {
        waitUntil(new DefaultCondition() {
            public boolean test() throws Exception {
                return existsSubItem(subItemText);
            }

            public String getFailureMessage() {
                return "The tree node" + "doesn't contain the treeItem"
                    + subItemText;
            }
        });
    }

    public boolean isContextMenuEnabled(String... contextNames)
        throws RemoteException {
        return ContextMenuHelper.isContextMenuEnabled(swtBotTree, contextNames);
    }

    public boolean existsContextMenu(String... contextNames)
        throws RemoteException {
        return ContextMenuHelper.existsContextMenu(swtBotTree, contextNames);
    }

}