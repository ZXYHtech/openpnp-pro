/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui.support;

import java.awt.Component;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JDialog;
import javax.swing.JOptionPane;

import org.openpnp.gui.MainFrame;
import org.pmw.tinylog.Logger;

public class MessageBoxes {
    public interface ErrorListener {
        void errorShown(String title, String message);

        default void errorDialogShown(String title, String message, JDialog dialog) {
        }
    }

    private static final List<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();

    public static void addErrorListener(ErrorListener listener) {
        errorListeners.add(listener);
    }

    public static void removeErrorListener(ErrorListener listener) {
        errorListeners.remove(listener);
    }

    private static void notifyErrorListeners(String title, String message) {
        for (ErrorListener listener : errorListeners) {
            try {
                listener.errorShown(title, message);
            }
            catch (RuntimeException e) {
                Logger.warn(e, "Unable to notify an error listener.");
            }
        }
    }

    private static void notifyErrorDialogListeners(String title, String message,
            JDialog dialog) {
        for (ErrorListener listener : errorListeners) {
            try {
                listener.errorDialogShown(title, message, dialog);
            }
            catch (RuntimeException e) {
                Logger.warn(e, "Unable to link an error dialog to a listener.");
            }
        }
    }

    private static Object showErrorDialog(Component parent, String title, String listenerMessage,
            String displayMessage, int optionType) {
        JOptionPane optionPane = new JOptionPane(displayMessage, JOptionPane.ERROR_MESSAGE,
                optionType);
        JDialog dialog = optionPane.createDialog(parent, title);
        notifyErrorDialogListeners(title, listenerMessage, dialog);
        dialog.setVisible(true);
        Object value = optionPane.getValue();
        dialog.dispose();
        return value;
    }

    // prepare message for use in a message box
    static String prepareMessage(String message) {
        if (message == null) {
            message = "";
        }
        message = message.replaceAll("\n", "<br/>");
        message = message.replaceAll("\r", "");
        message = "<html><body width=\"400\">" + message + "</body></html>";
        return message;
    }    

    public static boolean errorBox(Component parent, String title, Throwable cause, boolean withContinuation) {
        String message = null;
        boolean ret = false;
        if (cause != null) {
            message = cause.getMessage();
            if (message == null || message.trim().isEmpty()) {
                StringWriter stringWriter = new StringWriter();
                PrintWriter writer = new PrintWriter(stringWriter);
                cause.printStackTrace(writer);
                writer.close();
                message = stringWriter.toString();
            }
        }
        if (message == null) {
            message = "No message supplied.";
        }
        Logger.debug("{}: {}", title, cause);
        String listenerMessage = message;
        notifyErrorListeners(title, listenerMessage);
        message = message.replaceAll("<", "&lt;");
        message = message.replaceAll(">", "&gt;");
        message = prepareMessage(message);

        // if this errorBox shall ask for Continuation, show a ConfirmDialog and return if the user selected YES
        if (withContinuation) {
            ret = Integer.valueOf(JOptionPane.OK_OPTION).equals(showErrorDialog(parent, title,
                    listenerMessage, message, JOptionPane.OK_CANCEL_OPTION));
        }
        else {
            showErrorDialog(parent, title, listenerMessage, message, JOptionPane.DEFAULT_OPTION);
        }
        
        return ret;
    }

    public static void errorBox(Component parent, String title, Throwable cause) {
        errorBox(parent, title, cause, false);
    }

    public static void errorBox(Component parent, String title, String message) {
        if (message == null) {
            message = "";
        }
        Logger.debug("{}: {}", title, message);
        String listenerMessage = message;
        notifyErrorListeners(title, listenerMessage);
        message = prepareMessage(message);
        showErrorDialog(parent, title, listenerMessage, message, JOptionPane.DEFAULT_OPTION);
    }

    public static boolean errorBoxWithRetry(Component parent, String title, String message) {
        if (message == null) {
            message = "";
        }
        Logger.debug("{}: {}", title, message);
        String listenerMessage = message;
        notifyErrorListeners(title, listenerMessage);
        message = prepareMessage(message);
        return Integer.valueOf(JOptionPane.YES_OPTION).equals(showErrorDialog(parent, title,
                listenerMessage, message, JOptionPane.YES_NO_OPTION));
    }

    public static void infoBox(String title, String message) {
        if (message == null) {
            message = "";
        }
        message = prepareMessage(message);
        JOptionPane.showMessageDialog(MainFrame.get(), message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static void notYetImplemented(Component parent) {
        errorBox(parent, "Not Yet Implemented", "This function is not yet implemented.");
    }
}
