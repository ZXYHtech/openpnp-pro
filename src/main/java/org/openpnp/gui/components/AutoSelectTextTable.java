package org.openpnp.gui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Vector;
import java.util.prefs.Preferences;

import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.DefaultRowSorter;
import javax.swing.JScrollPane;
import javax.swing.JPopupMenu;
import javax.swing.JViewport;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import javax.swing.text.JTextComponent;

import org.openpnp.gui.support.TableUtils;

/**
 * From http://tips4java.wordpress.com/2008/10/20/table-select-all-editor/
 * 
 * The RXTable provides some extensions to the default JTable
 *
 * 1) Select All editing - when a text related cell is placed in editing mode the text is selected.
 * Controlled by invoking a "setSelectAll..." method.
 *
 * 2) reorderColumns - static convenience method for reodering table columns
 */
@SuppressWarnings("serial")
public class AutoSelectTextTable extends JTable {
    private boolean isSelectAllForMouseEvent = true;
    private boolean isSelectAllForActionEvent = true;
    private boolean isSelectAllForKeyEvent = true;
    private MouseAdapter sortColumnCycler = null;
    private boolean multiRowEditEnabled;
    private RowReorderHandler rowReorderHandler;
    private Point rowDragStart;
    private List<Integer> draggedModelRows;
    private int rowDropViewIndex = -1;
    private boolean rightRowDragActive;
    private JPopupMenu suspendedPopupMenu;

    @FunctionalInterface
    public interface RowReorderHandler {
        boolean reorderRows(List<Integer> modelRows, int targetModelRow);
    }
    //
    // Constructors
    //
    /**
     * Constructs a default <code>RXTable</code> that is initialized with a default data model, a
     * default column model, and a default selection model.
     */
    public AutoSelectTextTable() {
        this(null, null, null);
    }

    /**
     * Constructs a <code>RXTable</code> that is initialized with <code>dm</code> as the data model,
     * a default column model, and a default selection model.
     *
     * @param dm the data model for the table
     */
    public AutoSelectTextTable(TableModel dm) {
        this(dm, null, null);
    }

    /**
     * Constructs a <code>RXTable</code> that is initialized with <code>dm</code> as the data model,
     * <code>cm</code> as the column model, and a default selection model.
     *
     * @param dm the data model for the table
     * @param cm the column model for the table
     */
    public AutoSelectTextTable(TableModel dm, TableColumnModel cm) {
        this(dm, cm, null);
    }

    /**
     * Constructs a <code>RXTable</code> that is initialized with <code>dm</code> as the data model,
     * <code>cm</code> as the column model, and <code>sm</code> as the selection model. If any of
     * the parameters are <code>null</code> this method will initialize the table with the
     * corresponding default model. The <code>autoCreateColumnsFromModel</code> flag is set to false
     * if <code>cm</code> is non-null, otherwise it is set to true and the column model is populated
     * with suitable <code>TableColumns</code> for the columns in <code>dm</code>.
     *
     * @param dm the data model for the table
     * @param cm the column model for the table
     * @param sm the row selection model for the table
     */
    public AutoSelectTextTable(TableModel dm, TableColumnModel cm, ListSelectionModel sm) {
        super(dm, cm, sm);
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JTableHeader columnHeader = new JTableHeader(getColumnModel()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                paintColumnBoundaries(graphics, getHeight());
            }

            @Override
            public String getToolTipText(MouseEvent event) {
                int column = columnAtPoint(event.getPoint());
                if (column < 0) {
                    return null;
                }
                Object name = getColumnModel().getColumn(column).getHeaderValue();
                return toTooltipHtml(String.valueOf(name)
                        + "\nDouble-click to automatically fit the columns");
            }
        };
        setTableHeader(columnHeader);
        getTableHeader().setResizingAllowed(true);
        getTableHeader().setToolTipText(
                "Double-click to fit all columns; double-click a divider to fit one column");
        setToolTipText(" ");
        getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 2) {
                    int viewColumn = getTableHeader().columnAtPoint(event.getPoint());
                    if (viewColumn >= 0 && isNearRightColumnBoundary(viewColumn, event.getX())) {
                        autoFitColumn(viewColumn);
                    }
                    else {
                        autoFitColumnsToViewport();
                    }
                    event.consume();
                }
            }
        });
        
        //Add a keystroke to de-select all rows of the table (in Windows this would be Ctrl-Shift-A)
        InputMap im = getInputMap(JTable.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        //Should use getMenuShortcutKeyMaskEx here but it is not supported in Java 8
        final int CMD_BTN = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK | CMD_BTN),
                "clearSelection" );
        if (dm instanceof RowReorderableTableModel) {
            setRowReorderHandler((rows, target) -> {
                boolean changed = ((RowReorderableTableModel) dm).reorderRows(rows, target);
                if (changed && getRowSorter() instanceof DefaultRowSorter) {
                    ((DefaultRowSorter<?, ?>) getRowSorter()).setSortKeys(null);
                }
                return changed;
            });
        }
    }


    /**
     * Enables right-button drag reordering for selected rows. The callback performs the persistent
     * model update; this table supplies selected model rows and the model row before which they
     * should be inserted.
     */
    public void setRowReorderHandler(RowReorderHandler handler) {
        rowReorderHandler = handler;
        if (handler == null || getClientProperty("openpnp.rightRowDrag") != null) {
            return;
        }
        putClientProperty("openpnp.rightRowDrag", Boolean.TRUE);
        MouseAdapter rowDragListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    beginRightRowDrag(event);
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if ((event.getModifiersEx() & InputEvent.BUTTON3_DOWN_MASK) != 0) {
                    updateRightRowDrag(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    finishRightRowDrag(event);
                }
            }
        };
        addMouseListener(rowDragListener);
        addMouseMotionListener(rowDragListener);
    }

    private void beginRightRowDrag(MouseEvent event) {
        int row = rowAtPoint(event.getPoint());
        if (row < 0) {
            return;
        }
        if (!isRowSelected(row)) {
            setRowSelectionInterval(row, row);
        }
        rowDragStart = event.getPoint();
        draggedModelRows = new ArrayList<>();
        for (int selectedViewRow : getSelectedRows()) {
            draggedModelRows.add(convertRowIndexToModel(selectedViewRow));
        }
        rowDropViewIndex = row;
        rightRowDragActive = false;
        // Suspend the popup before Swing can open it on mouse press. A normal right click is
        // restored in finishRightRowDrag().
        suspendedPopupMenu = getComponentPopupMenu();
        setComponentPopupMenu(null);
    }

    private void updateRightRowDrag(MouseEvent event) {
        if (rowDragStart == null || draggedModelRows == null) {
            return;
        }
        if (!rightRowDragActive && rowDragStart.distance(event.getPoint()) < 5) {
            return;
        }
        if (!rightRowDragActive) {
            rightRowDragActive = true;
        }
        int row = rowAtPoint(event.getPoint());
        if (row < 0) {
            rowDropViewIndex = event.getY() < 0 ? 0 : getRowCount();
        }
        else {
            Rectangle bounds = getCellRect(row, 0, true);
            rowDropViewIndex = event.getY() < bounds.y + bounds.height / 2 ? row : row + 1;
        }
        repaint();
        event.consume();
    }

    private void finishRightRowDrag(MouseEvent event) {
        boolean completedDrag = rightRowDragActive;
        if (completedDrag && rowReorderHandler != null && draggedModelRows != null) {
            int targetModelRow = getModel().getRowCount();
            if (rowDropViewIndex >= 0 && rowDropViewIndex < getRowCount()) {
                targetModelRow = convertRowIndexToModel(rowDropViewIndex);
            }
            rowReorderHandler.reorderRows(new ArrayList<>(draggedModelRows), targetModelRow);
            event.consume();
        }
        JPopupMenu popupMenu = suspendedPopupMenu;
        rowDragStart = null;
        draggedModelRows = null;
        rowDropViewIndex = -1;
        rightRowDragActive = false;
        suspendedPopupMenu = null;
        if (!completedDrag && popupMenu != null) {
            popupMenu.show(this, event.getX(), event.getY());
            event.consume();
        }
        SwingUtilities.invokeLater(() -> setComponentPopupMenu(popupMenu));
        repaint();
    }
    /**
     * Enables spreadsheet-style batch editing. When several rows are selected, committing an edit
     * copies the value to the same editable model column in every selected row.
     */
    public void setMultiRowEditEnabled(boolean multiRowEditEnabled) {
        this.multiRowEditEnabled = multiRowEditEnabled;
    }

    @Override
    public void editingStopped(ChangeEvent event) {
        TableCellEditor editor = getCellEditor();
        int editingViewRow = getEditingRow();
        int editingViewColumn = getEditingColumn();
        int editingModelRow = editingViewRow < 0 ? -1 : convertRowIndexToModel(editingViewRow);
        int modelColumn = editingViewColumn < 0 ? -1 : convertColumnIndexToModel(editingViewColumn);
        Object value = editor == null ? null : editor.getCellEditorValue();
        List<Integer> selectedModelRows = new ArrayList<>();
        if (multiRowEditEnabled && modelColumn >= 0) {
            for (int viewRow : getSelectedRows()) {
                selectedModelRows.add(convertRowIndexToModel(viewRow));
            }
        }

        super.editingStopped(event);

        if (multiRowEditEnabled && selectedModelRows.size() > 1) {
            TableModel model = getModel();
            for (int modelRow : selectedModelRows) {
                if (modelRow != editingModelRow && model.isCellEditable(modelRow, modelColumn)) {
                    model.setValueAt(value, modelRow, modelColumn);
                }
            }
            repaint();
        }
    }
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        paintColumnBoundaries(graphics, getHeight());
        paintRowDropIndicator(graphics);
    }

    private void paintRowDropIndicator(Graphics graphics) {
        if (!rightRowDragActive || rowDropViewIndex < 0) {
            return;
        }
        int y = rowDropViewIndex >= getRowCount() ? getRowCount() * getRowHeight()
                : getCellRect(rowDropViewIndex, 0, true).y;
        Graphics2D graphics2d = (Graphics2D) graphics.create();
        graphics2d.setColor(new Color(52, 120, 246));
        graphics2d.setStroke(new BasicStroke(2f));
        graphics2d.drawLine(0, y, getWidth(), y);
        graphics2d.dispose();
    }

    private void paintColumnBoundaries(Graphics graphics, int height) {
        if (getColumnCount() < 2) {
            return;
        }

        Graphics2D graphics2d = (Graphics2D) graphics.create();
        Stroke previousStroke = graphics2d.getStroke();
        Color previousColor = graphics2d.getColor();
        graphics2d.setColor(new Color(128, 128, 128, 115));
        graphics2d.setStroke(new BasicStroke(1f));
        int x = 0;
        for (int column = 0; column < getColumnCount() - 1; column++) {
            x += getColumnModel().getColumn(column).getWidth();
            graphics2d.drawLine(x - 1, 0, x - 1, height);
        }
        graphics2d.setStroke(previousStroke);
        graphics2d.setColor(previousColor);
        graphics2d.dispose();
    }
    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> {
            installAutoWidthButton();
            installPersistentColumnWidths();
        });
    }

    private void installPersistentColumnWidths() {
        Component owner = getParent();
        while (owner != null && (owner.getClass().getName().startsWith("javax.swing")
                || owner.getClass().getName().startsWith("java.awt"))) {
            owner = owner.getParent();
        }
        String ownerName = owner == null ? "table" : owner.getClass().getName();
        String identity = ownerName + ":" + getModel().getClass().getName()
                + ":" + String.valueOf(getName());
        String prefKey = "AutoTable." + Integer.toHexString(identity.hashCode()) + ".columnWidth";
        Preferences preferences = Preferences.userNodeForPackage(AutoSelectTextTable.class);
        TableUtils.installColumnWidthSavers(this, preferences, prefKey);
    }

    private void installAutoWidthButton() {
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(
                JScrollPane.class, this);
        if (scrollPane == null || scrollPane.getClientProperty("openpnp.autoWidthTable") == this) {
            return;
        }
        JButton autoWidth = new JButton("\u2194");
        autoWidth.setFocusable(false);
        autoWidth.setMargin(new java.awt.Insets(0, 0, 0, 0));
        autoWidth.setToolTipText("Automatically fit columns to the current page width");
        autoWidth.addActionListener(event -> autoFitColumnsToViewport());
        scrollPane.putClientProperty("openpnp.autoWidthTable", this);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setCorner(JScrollPane.UPPER_RIGHT_CORNER, autoWidth);
    }

    private boolean isNearRightColumnBoundary(int viewColumn, int mouseX) {
        Rectangle headerRectangle = getTableHeader().getHeaderRect(viewColumn);
        return Math.abs(mouseX - (headerRectangle.x + headerRectangle.width)) <= 5;
    }

    public void autoFitColumnsToViewport() {
        int columnCount = getColumnCount();
        if (columnCount == 0) {
            return;
        }
        int availableWidth = getVisibleRect().width;
        if (getParent() instanceof JViewport) {
            availableWidth = ((JViewport) getParent()).getExtentSize().width;
        }
        if (availableWidth <= 0) {
            availableWidth = getWidth();
        }
        availableWidth = Math.max(32 * columnCount, availableWidth);

        int[] preferred = new int[columnCount];
        int[] widths = new int[columnCount];
        boolean[] longTextColumns = new boolean[columnCount];
        List<Integer> allocationOrder = new ArrayList<>();
        int usedWidth = 0;
        for (int column = 0; column < columnCount; column++) {
            int measuredWidth = measureColumnWidth(column);
            longTextColumns[column] = getRowCount() > 0 && measuredWidth > 260;
            preferred[column] = adjustedColumnWidth(measuredWidth, longTextColumns[column]);
            widths[column] = 32;
            usedWidth += widths[column];
            allocationOrder.add(column);
        }
        allocationOrder.sort((left, right) -> Integer.compare(preferred[left], preferred[right]));

        int remaining = Math.max(0, availableWidth - usedWidth);
        // Narrow columns are satisfied first. Wide free-text columns therefore receive space last.
        for (int column : allocationOrder) {
            int requested = Math.max(0, preferred[column] - widths[column]);
            int granted = Math.min(requested, remaining);
            widths[column] += granted;
            remaining -= granted;
        }
        if (remaining > 0) {
            // Never expand a long-text column beyond its 50% target. Spare page width is shared by
            // the shorter operational columns instead.
            int expandableColumns = 0;
            for (boolean longTextColumn : longTextColumns) {
                if (!longTextColumn) {
                    expandableColumns++;
                }
            }
            if (expandableColumns > 0) {
                int perColumn = remaining / expandableColumns;
                int remainder = remaining % expandableColumns;
                for (int column = 0; column < columnCount; column++) {
                    if (!longTextColumns[column]) {
                        int extraPixel = remainder > 0 ? 1 : 0;
                        if (remainder > 0) {
                            remainder--;
                        }
                        widths[column] += perColumn + extraPixel;
                    }
                }
            }
        }

        for (int column = 0; column < columnCount; column++) {
            TableColumn tableColumn = getColumnModel().getColumn(column);
            tableColumn.setPreferredWidth(widths[column]);
            tableColumn.setWidth(widths[column]);
        }
        revalidate();
        repaint();
    }

    private void autoFitColumn(int viewColumn) {
        TableColumn column = getColumnModel().getColumn(viewColumn);
        int viewportWidth = getParent() instanceof JViewport
                ? ((JViewport) getParent()).getExtentSize().width : getWidth();
        int width = Math.min(calculateNaturalColumnWidth(viewColumn),
                Math.max(80, (int) (viewportWidth * 0.65)));
        column.setPreferredWidth(width);
        column.setWidth(width);
        revalidate();
        repaint();
    }

    private int calculateNaturalColumnWidth(int viewColumn) {
        int measuredWidth = measureColumnWidth(viewColumn);
        return adjustedColumnWidth(measuredWidth, getRowCount() > 0 && measuredWidth > 260);
    }

    private int adjustedColumnWidth(int measuredWidth, boolean longTextColumn) {
        if (longTextColumn) {
            // Long free-text columns use exactly half the width needed for the sampled full value.
            // The full value is always available on hover.
            return Math.max(130, Math.min(600, (measuredWidth + 1) / 2));
        }
        return Math.max(42, Math.min(600, measuredWidth));
    }

    private int measureColumnWidth(int viewColumn) {
        int rowCount = getRowCount();
        if (rowCount == 0) {
            TableColumn column = getColumnModel().getColumn(viewColumn);
            TableCellRenderer headerRenderer = column.getHeaderRenderer();
            if (headerRenderer == null) {
                headerRenderer = getTableHeader().getDefaultRenderer();
            }
            Component header = headerRenderer.getTableCellRendererComponent(this,
                    column.getHeaderValue(), false, false, -1, viewColumn);
            return header.getPreferredSize().width + 18;
        }

        // When rows exist, only their values influence width. Header text must not make a data
        // column wider; its complete name remains available through the header tooltip.
        int contentWidth = 42;
        int sampleCount = Math.min(rowCount, 200);
        for (int sample = 0; sample < sampleCount; sample++) {
            int row = sampleCount == rowCount ? sample
                    : (int) (((long) sample * rowCount) / sampleCount);
            TableCellRenderer renderer = getCellRenderer(row, viewColumn);
            Component component = prepareRenderer(renderer, row, viewColumn);
            contentWidth = Math.max(contentWidth, component.getPreferredSize().width + 16);
        }
        return contentWidth;
    }
    @Override
    public String getToolTipText(MouseEvent event) {
        int row = rowAtPoint(event.getPoint());
        int column = columnAtPoint(event.getPoint());
        if (row < 0 || column < 0) {
            return null;
        }
        Object value = getValueAt(row, column);
        if (value == null) {
            return null;
        }
        TableCellRenderer renderer = getCellRenderer(row, column);
        Component component = prepareRenderer(renderer, row, column);
        if (component.getPreferredSize().width + 16
                <= getColumnModel().getColumn(column).getWidth()) {
            return null;
        }
        return toTooltipHtml(String.valueOf(value));
    }

    private static String toTooltipHtml(String value) {
        String escaped = value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><body width=\"480\">" + escaped + "</body></html>";
    }
    /**
     * Constructs a <code>RXTable</code> with <code>numRows</code> and <code>numColumns</code> of
     * empty cells using <code>DefaultTableModel</code>. The columns will have names of the form
     * "A", "B", "C", etc.
     *
     * @param numRows the number of rows the table holds
     * @param numColumns the number of columns the table holds
     */
    public AutoSelectTextTable(int numRows, int numColumns) {
        this(new DefaultTableModel(numRows, numColumns));
    }

    /**
     * Constructs a <code>RXTable</code> to display the values in the <code>Vector</code> of
     * <code>Vectors</code>, <code>rowData</code>, with column names, <code>columnNames</code>. The
     * <code>Vectors</code> contained in <code>rowData</code> should contain the values for that
     * row. In other words, the value of the cell at row 1, column 5 can be obtained with the
     * following code:
     * <p>
     * 
     * <pre>
     * ((Vector) rowData.elementAt(1)).elementAt(5);
     * </pre>
     * <p>
     * 
     * @param rowData the data for the new table
     * @param columnNames names of each column
     */
    public AutoSelectTextTable(Vector rowData, Vector columnNames) {
        this(new DefaultTableModel(rowData, columnNames));
    }

    /**
     * Constructs a <code>RXTable</code> to display the values in the two dimensional array,
     * <code>rowData</code>, with column names, <code>columnNames</code>. <code>rowData</code> is an
     * array of rows, so the value of the cell at row 1, column 5 can be obtained with the following
     * code:
     * <p>
     * 
     * <pre>
     *  rowData[1][5];
     * </pre>
     * <p>
     * All rows must be of the same length as <code>columnNames</code>.
     * <p>
     * 
     * @param rowData the data for the new table
     * @param columnNames names of each column
     */
    public AutoSelectTextTable(final Object[][] rowData, final Object[] columnNames) {
        super(rowData, columnNames);
    }

    //
    // Overridden methods
    //
    /*
     * Override to provide Select All editing functionality
     */
    public boolean editCellAt(int row, int column, EventObject e) {
        TableCellEditor editor = getCellEditor(row, column);
        if (!isCellSelected(row, column)) {
            // do not show editor when clicked cell from outside. First select cell and next open editor
            return false;
        }
        boolean result = super.editCellAt(row, column, e);

        if (isSelectAllForMouseEvent || isSelectAllForActionEvent || isSelectAllForKeyEvent) {
            selectAll(e);
        }

        return result;
    }

    /*
     * Select the text when editing on a text related cell is started
     */
    private void selectAll(EventObject e) {
        final Component editor = getEditorComponent();

        if (editor == null || !(editor instanceof JTextComponent)) {
            return;
        }

        if (e == null) {
            ((JTextComponent) editor).selectAll();
            return;
        }

        // Typing in the cell was used to activate the editor

        if (e instanceof KeyEvent && isSelectAllForKeyEvent) {
            ((JTextComponent) editor).selectAll();
            return;
        }

        // F2 was used to activate the editor

        if (e instanceof ActionEvent && isSelectAllForActionEvent) {
            ((JTextComponent) editor).selectAll();
            return;
        }

        // A mouse click was used to activate the editor.
        // Generally this is a double click and the second mouse click is
        // passed to the editor which would remove the text selection unless
        // we use the invokeLater()

        if (e instanceof MouseEvent && isSelectAllForMouseEvent) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    ((JTextComponent) editor).selectAll();
                }
            });
        }
    }

    //
    // Newly added methods
    //
    /*
     * Sets the Select All property for for all event types
     */
    public void setSelectAllForEdit(boolean isSelectAllForEdit) {
        setSelectAllForMouseEvent(isSelectAllForEdit);
        setSelectAllForActionEvent(isSelectAllForEdit);
        setSelectAllForKeyEvent(isSelectAllForEdit);
    }

    /*
     * Set the Select All property when editing is invoked by the mouse
     */
    public void setSelectAllForMouseEvent(boolean isSelectAllForMouseEvent) {
        this.isSelectAllForMouseEvent = isSelectAllForMouseEvent;
    }

    /*
     * Set the Select All property when editing is invoked by the "F2" key
     */
    public void setSelectAllForActionEvent(boolean isSelectAllForActionEvent) {
        this.isSelectAllForActionEvent = isSelectAllForActionEvent;
    }

    /*
     * Set the Select All property when editing is invoked by typing directly into the cell
     */
    public void setSelectAllForKeyEvent(boolean isSelectAllForKeyEvent) {
        this.isSelectAllForKeyEvent = isSelectAllForKeyEvent;
    }

    // public void changeSelection(final int row, final int column, boolean toggle, boolean extend)
    // {
    // super.changeSelection(row, column, toggle, extend);
    // editCellAt(row, column);
    // transferFocus();
    // }

    @Override
    public void setRowSorter(RowSorter<? extends TableModel> sorter) {
        super.setRowSorter(sorter);
        // Change the sorting order clicking to work with three states, ASCENDING>DESCENDING>UNSORTED instead of two.
        if (sortColumnCycler == null) {
            sortColumnCycler = new MouseAdapter() {

                private List<SortKey> lastSortKeys = new ArrayList<>();

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                        int column = convertColumnIndexToModel(getTableHeader().columnAtPoint(e.getPoint()));
                        SortOrder sortOrder = SortOrder.ASCENDING;
                        for (RowSorter.SortKey sortKey : lastSortKeys) {
                            if (column == sortKey.getColumn()) {
                                switch (sortKey.getSortOrder()) {
                                    case UNSORTED:
                                        sortOrder = SortOrder.ASCENDING;
                                        break;
                                    case ASCENDING:
                                        sortOrder = SortOrder.DESCENDING;
                                        break;
                                    case DESCENDING:
                                        sortOrder = SortOrder.UNSORTED;
                                        break;
                                }
                                break;
                            }
                        }
                        // Create the new sort keys list starting with the newly clicked one.
                        List<SortKey> sortKeys = new ArrayList<>();
                        RowSorter.SortKey sortKeyClicked = new RowSorter.SortKey(column, sortOrder);
                        sortKeys.add(sortKeyClicked);
                        // Add the rest.
                        for (RowSorter.SortKey sortKey : lastSortKeys) {
                            if (column != sortKey.getColumn()) {
                                sortKeys.add(sortKey);
                            }
                        }
                        RowSorter<?> sorter = getRowSorter();
                        sorter.setSortKeys(sortKeys);
                        this.lastSortKeys = sortKeys;
                    }
                }
            };
        }
        getTableHeader().removeMouseListener(sortColumnCycler);
        getTableHeader().addMouseListener(sortColumnCycler);
    }

    //
    // Static, convenience methods
    //
    /**
     * Convenience method to order the table columns of a table. The columns are ordered based on
     * the column names specified in the array. If the column name is not found then no column is
     * moved. This means you can specify a null value to preserve the current order of a given
     * column.
     *
     * @param table the table containing the columns to be sorted
     * @param columnNames an array containing the column names in the order they should be displayed
     */
    public static void reorderColumns(JTable table, Object... columnNames) {
        TableColumnModel model = table.getColumnModel();

        for (int newIndex = 0; newIndex < columnNames.length; newIndex++) {
            try {
                Object columnName = columnNames[newIndex];
                int index = model.getColumnIndex(columnName);
                model.moveColumn(index, newIndex);
            }
            catch (IllegalArgumentException e) {
            }
        }
    }
} // End of Class RXTable
