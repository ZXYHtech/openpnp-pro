/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is package of OpenPnP.
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

package org.openpnp.gui.tablemodel;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import org.openpnp.Translations;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.FiducialVisionSettings;
import org.openpnp.model.Package;
import org.openpnp.packageimport.PackageCategoryRegistry;
import org.openpnp.packageimport.PackageCategoryRegistry.Category;
import org.openpnp.gui.components.RowReorderableTableModel;
import org.openpnp.gui.support.RowReorderSupport;

@SuppressWarnings("serial")
public class PackagesTableModel extends AbstractObjectTableModel
        implements PropertyChangeListener, RowReorderableTableModel {
    final private Configuration configuration;

    private String[] columnNames = new String[] {
            Translations.getString("PackagesTableModel.ColumnName.ID"), //$NON-NLS-1$
            Translations.getString("PackagesTableModel.ColumnName.Description"), //$NON-NLS-1$
            Translations.getString("PackagesTableModel.ColumnName.TapeSpecification"), //$NON-NLS-1$
            Translations.getString("PackagesTableModel.ColumnName.BottomVision"), //$NON-NLS-1$
            Translations.getString("PackagesTableModel.ColumnName.FiducialVision"), //$NON-NLS-1$
            "标准名",
            "别名",
            "分类",
            "审核状态",
            "来源",
            "重复组"
    };
    private Class[] columnTypes = new Class[] {String.class, String.class, String.class,
            BottomVisionSettings.class, FiducialVisionSettings.class, String.class, String.class,
            Category.class, Package.ReviewState.class, Package.SourceType.class, String.class};
    private List<Package> packages;

    public PackagesTableModel(Configuration configuration) {
        this.configuration = configuration;
        configuration.addPropertyChangeListener("packages", this);
        packages = new ArrayList<>(configuration.getPackages());

    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    public int getColumnCount() {
        return columnNames.length;
    }

    public int getRowCount() {
        return (packages == null) ? 0 : packages.size();
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnTypes[columnIndex];
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex != 0 && columnIndex != 9 && columnIndex != 10;
    }

    @Override
    public Package getRowObjectAt(int index) {
        return packages.get(index);
    }

    @Override
    public int indexOf(Object selectedPackage) {
        return packages.indexOf(selectedPackage);
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        try {
            Package this_package = packages.get(rowIndex);
            if (columnIndex == 1) {
                this_package.setDescription((String) aValue);
            }
            else { if (columnIndex == 2) {
                this_package.setTapeSpecification((String) aValue);
            }
            else { if (columnIndex == 3) {
                this_package.setBottomVisionSettings((BottomVisionSettings) aValue);
            }
            else { if (columnIndex == 4) {
                this_package.setFiducialVisionSettings((FiducialVisionSettings) aValue);
            }
            else { if (columnIndex == 5) {
                this_package.setStandardName((String) aValue);
            }
            else { if (columnIndex == 6) {
                this_package.setAliases((String) aValue);
            }
            else { if (columnIndex == 7) {
                Category category = (Category) aValue;
                this_package.setCategoryId(category == null ? null : category.id);
                this_package.setClassificationConfidence(1.0);
                this_package.setClassificationReason("用户手工分类");
                if (this_package.getReviewState() != Package.ReviewState.VERIFIED) {
                    this_package.setReviewState(Package.ReviewState.NEEDS_REVIEW);
                }
            }
            else { if (columnIndex == 8) {
                this_package.setReviewState((Package.ReviewState) aValue);
            } } } } } } } }
        }
        catch (Exception e) {
            // TODO: dialog, bad input
        }
    }

    public Object getValueAt(int row, int col) {
        Package this_package = packages.get(row);
        switch (col) {
            case 0:
                return this_package.getId();
            case 1:
                return this_package.getDescription();
            case 2:
                return this_package.getTapeSpecification();
            case 3:
                return this_package.getBottomVisionSettings();
            case 4:
                return this_package.getFiducialVisionSettings();
            case 5:
                return this_package.getStandardName();
            case 6:
                return this_package.getAliases();
            case 7:
                return PackageCategoryRegistry.get(this_package.getCategoryId());
            case 8:
                return this_package.getReviewState();
            case 9:
                return this_package.getSourceType();
            case 10:
                return this_package.getDuplicateGroupId();
            default:
                return null;
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent arg0) {
        packages = new ArrayList<>(configuration.getPackages());
        fireTableDataChanged();
    }
    @Override
    public boolean reorderRows(List<Integer> rows, int targetRow) {
        List<Package> ordered = RowReorderSupport.reorder(packages, rows, targetRow);
        return ordered != null && configuration.reorderPackages(ordered);
    }

}
