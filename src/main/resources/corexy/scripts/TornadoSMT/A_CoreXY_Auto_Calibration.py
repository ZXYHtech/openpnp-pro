from org.openpnp.corexy.ui import CoreXYWizard
from org.openpnp.gui import MainFrame
from org.openpnp.model import Configuration
from javax.swing import SwingUtilities
from java.lang import Runnable

class OpenCalibration(Runnable):
    def run(self):
        CoreXYWizard(MainFrame.get(), Configuration.get()).setVisible(True)

SwingUtilities.invokeLater(OpenCalibration())
