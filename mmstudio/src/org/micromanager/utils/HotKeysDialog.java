
/*
 * HotKeys.java
 *
 * Created on Apr 11, 2011, 3:59:44 PM
 */

package org.micromanager.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.AbstractCellEditor;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import org.micromanager.utils.FileDialogs.FileType;

/**
 *
 * @author nico
 */
public final class HotKeysDialog extends MMDialog {
   private ShortCutTableModel sctModel_ = new ShortCutTableModel();
   private JComboBox combo_ = new JComboBox();
   private Integer lastTypedKey_ = 0;
   private KeyEvtHandler keh_;
   private ArrayList<Integer> keys_ = new ArrayList<Integer>();
   private ArrayList<HotKeyAction> actions_ = new ArrayList<HotKeyAction>();
   private ArrayList<HotKeyAction> possibleActions_ = new ArrayList<HotKeyAction>();
   private String[] possibleActionsAsString_;
   private Font ourFont_ = new Font("Lucida Grande", java.awt.Font.PLAIN, 10);
   private Preferences prefs_;

   public static FileType MM_HOTKEYS
           = new FileType("MM_HOTKEYS",
                 "Micro-Manager HotKeys",
                 System.getProperty("user.home") + "/MMHotKeys",
                 false, (String[]) null);
 
   public class ShortCutTableModel extends AbstractTableModel {

      private static final int columnCount_ = 2;

      public int getRowCount() {
         if (keys_ != null)
            return keys_.size();
         return 0;
      }

      public int getColumnCount() {
         return columnCount_;
      }

      @Override
      public String getColumnName(int columnIndex) {
         if (columnIndex == 0)
            return "Action";
         return "HotKey";
      }

      public Object getValueAt(int row, int column) {
         if (column == 0) {
            if (row > actions_.size())
               return null;
            HotKeyAction action = actions_.get(row);
            if (action != null) {
               if (action.type_ == HotKeyAction.GUICOMMAND) {
                  return HotKeyAction.guiItems_[action.guiCommand_];
               } else
                  return action.beanShellScript_.getName();
            }
         }
         if (column == 1) {
            if (row > keys_.size())
               return null;
            return KeyEvent.getKeyText(keys_.get(row));
         }
         return null;
      }

      @Override
      public boolean isCellEditable(int row, int col) {
         return true;
      }

      @Override
      public void setValueAt(Object value, int row, int col) {
         boolean found = false;
         if (col == 0) {
            for (int i = 0; i < possibleActionsAsString_.length && !found; i++) {
               if (possibleActionsAsString_[i].equals(value)) {
                  found = true;
                  actions_.set(row,possibleActions_.get(i));
               }
            }
         }
         if (col == 1) {
            // keep the keys unique
            Iterator it = keys_.listIterator();
            while (!found && it.hasNext()) {
               if (value.equals(it.next()))
                  found = true;
            }
            if (!found)
               keys_.set(row, (Integer) value);
         }

         fireTableCellUpdated(row, col);

      }
   }


    /** Creates new form HotKeys */
    public  HotKeysDialog(Color backgroundColor) {
        initComponents();

        this.setBackground(backgroundColor);

        Preferences root = Preferences.userNodeForPackage(this.getClass());
        prefs_ = root.node(root.absolutePath() + "/HotKeyFrame");
        setPrefsNode(prefs_);
        loadPosition(0, 0, 377, 378);
        //org.micromanager.MMStudioMainFrame.getInstance().addMMBackgroundListener(this);

        readKeys();

        HotKeys.active_ = false;

        addWindowListener(new WindowAdapter() {
           @Override
           public void windowClosing(WindowEvent arg0) {
              hotKeyTable_.getColumnModel().getColumn(0).getCellEditor().stopCellEditing();
              hotKeyTable_.getColumnModel().getColumn(1).getCellEditor().stopCellEditing();
              generateKeys();

              HotKeys.active_ = true;
              savePosition();
            }

        });

        updateComboBox();

        hotKeyTable_.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(combo_));
        hotKeyTable_.getColumnModel().getColumn(1).setCellEditor(new HotKeyCol1Editor());
        hotKeyTable_.getColumnModel().getColumn(1).setCellRenderer(new HotKeyCol1Renderer());
        

        keh_ = new KeyEvtHandler();
        hotKeyTable_.addKeyListener(keh_);

        setModal(true);
        setVisible(true);
    }

    /*
     * copy keys_ and actions_ back to HotKeys.keys_
     */
    private void generateKeys() {
       HotKeys.keys_.clear();
       for (int i = 0; i < keys_.size(); i++) {
          HotKeys.keys_.put(keys_.get(i), actions_.get(i));
       }
    }
    
   /*
    * copy the map with hotkeys and action temporarily into two ArrayLists
    * Those will be used by our table model and written back to HotKeys.keys_
    * on exit
    */
    private void readKeys() {
       keys_.clear();
       actions_.clear();
       Iterator it = HotKeys.keys_.entrySet().iterator();
       while (it.hasNext()) {
           Map.Entry pairs = (Map.Entry)it.next();
           keys_.add((Integer)pairs.getKey());
           actions_.add((HotKeyAction) pairs.getValue());
       }
    }

    public void updateComboBox() {
       // Add Beanshell scripts
       int nrScripts = 0;
       ArrayList<File> scriptList = org.micromanager.ScriptPanel.getScriptList();
       if (scriptList != null) {
          nrScripts = scriptList.size();
       }

       possibleActionsAsString_ = new String[HotKeyAction.NRGUICOMMANDS + nrScripts];
       System.arraycopy(HotKeyAction.guiItems_, 0, possibleActionsAsString_, 0, HotKeyAction.guiItems_.length);
       for (int i=0; i < HotKeyAction.NRGUICOMMANDS; i++) {
         possibleActions_.add(i, new HotKeyAction(i));
       }

       for (int i = 0; i < scriptList.size(); i++) {
             possibleActions_.add(i + HotKeyAction.NRGUICOMMANDS, new HotKeyAction(scriptList.get(i)));
             possibleActionsAsString_[i + HotKeyAction.NRGUICOMMANDS] = scriptList.get(i).getName();
       }

       DefaultComboBoxModel model = new DefaultComboBoxModel(possibleActionsAsString_);
       combo_.setModel(model);
    }

    public class HotKeyCol1Renderer extends DefaultTableCellRenderer {
       public void SetValue(Object value) {
          setText(KeyEvent.getKeyText((Integer) value));
       }
    }

    public class KeyEvtHandler implements KeyListener
    {
       JLabel label_;

       public void setLabel(JLabel label) {
          label_ = label;
       }

       public void keyTyped(KeyEvent ke) {
       }

       public void keyPressed(KeyEvent ke) {
          Integer value = ke.getKeyCode();
          boolean found = false;
          Iterator it = keys_.listIterator();
          while (!found && it.hasNext()) {
              if (value.equals(it.next()))
                 found = true;
          }
          if (!found) {
             lastTypedKey_ = value;
             if (label_ != null)
               label_.setText(KeyEvent.getKeyText(lastTypedKey_));
          }
       }

       public void keyReleased(KeyEvent ke) {
         //throw new UnsupportedOperationException("Not supported yet.");
       }
    }

    public class HotKeyCol1Editor extends AbstractCellEditor implements TableCellEditor {
       JLabel keyLabel = new JLabel();

       // This method is called when a cell value is edited by the user.
       public Component getTableCellEditorComponent(javax.swing.JTable table, Object value,
               boolean isSelected, int rowIndex, int colIndex) {
           // 'value' is value contained in the cell located at (rowIndex, colIndex)

           if (value != null)
              keyLabel.setText( (String) value);
           else
              keyLabel.setText("m");
           keyLabel.setForeground(Color.red);
           keyLabel.setFocusable(true);
           keyLabel.setFont(ourFont_);
           lastTypedKey_ = keys_.get(rowIndex);
           keh_.setLabel(keyLabel);
           return keyLabel;

       }

       // This method is called when editing is completed.
       // It must return the new value to be stored in the cell.
       public Object getCellEditorValue() {
           return (Integer) lastTypedKey_;
       }
   }

   
    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
   // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
   private void initComponents() {

      jScrollPane1_ = new javax.swing.JScrollPane();
      hotKeyTable_ = new javax.swing.JTable();
      addButton_ = new javax.swing.JButton();
      removeButton_ = new javax.swing.JButton();
      loadButton_ = new javax.swing.JButton();
      saveButton_ = new javax.swing.JButton();

      setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
      //setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

      jScrollPane1_.setMinimumSize(new java.awt.Dimension(23, 15));
      jScrollPane1_.setPreferredSize(new java.awt.Dimension(32767, 32767));

      hotKeyTable_.setFont(new java.awt.Font("Lucida Grande", 0, 10));
      hotKeyTable_.setModel(sctModel_);
      jScrollPane1_.setViewportView(hotKeyTable_);

      addButton_.setFont(new java.awt.Font("Lucida Grande", 0, 10));
      addButton_.setText("Add");
      addButton_.setMinimumSize(new java.awt.Dimension(75, 20));
      addButton_.setPreferredSize(new java.awt.Dimension(75, 20));
      addButton_.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent evt) {
            addButton_ActionPerformed(evt);
         }
      });

      removeButton_.setFont(new java.awt.Font("Lucida Grande", 0, 10));
      removeButton_.setText("Remove");
      removeButton_.setMinimumSize(new java.awt.Dimension(75, 20));
      removeButton_.setPreferredSize(new java.awt.Dimension(75, 20));
      removeButton_.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent evt) {
            removeButton_ActionPerformed(evt);
         }
      });

      loadButton_.setFont(new java.awt.Font("Lucida Grande", 0, 10));
      loadButton_.setText("Load");
      loadButton_.setMinimumSize(new java.awt.Dimension(75, 20));
      loadButton_.setPreferredSize(new java.awt.Dimension(75, 20));
      loadButton_.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent evt) {
            loadButton_ActionPerformed(evt);
         }
      });

      saveButton_.setFont(new java.awt.Font("Lucida Grande", 0, 10));
      saveButton_.setText("Save");
      saveButton_.setMinimumSize(new java.awt.Dimension(75, 20));
      saveButton_.setPreferredSize(new java.awt.Dimension(75, 20));
      saveButton_.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent evt) {
            saveButton_ActionPerformed(evt);
         }
      });

      org.jdesktop.layout.GroupLayout layout = new org.jdesktop.layout.GroupLayout(getContentPane());
      getContentPane().setLayout(layout);
      layout.setHorizontalGroup(
         layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
         .add(layout.createSequentialGroup()
            .add(addButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(removeButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED, 69, Short.MAX_VALUE)
            .add(loadButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(saveButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
         .add(jScrollPane1_, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 377, Short.MAX_VALUE)
      );
      layout.setVerticalGroup(
         layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
         .add(layout.createSequentialGroup()
            .add(layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
               .add(addButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 20, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
               .add(removeButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 20, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
               .add(saveButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 20, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
               .add(loadButton_, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 20, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(jScrollPane1_, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .add(0, 0, Short.MAX_VALUE))
      );

      pack();
   }// </editor-fold>//GEN-END:initComponents

    private void addButton_ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addButton_ActionPerformed
       keys_.add(32);
       actions_.add(new HotKeyAction(0));
       sctModel_.fireTableRowsInserted(keys_.size() - 1, keys_.size());
       sctModel_.fireTableDataChanged();
    }//GEN-LAST:event_addButton_ActionPerformed

    private void removeButton_ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeButton_ActionPerformed
       hotKeyTable_.getColumnModel().getColumn(0).getCellEditor().stopCellEditing();
       hotKeyTable_.getColumnModel().getColumn(1).getCellEditor().stopCellEditing();
       int[] rows = hotKeyTable_.getSelectedRows();
       for (int i=rows.length-1; i >= 0; i--) {
          keys_.remove(rows[i]);
          actions_.remove(rows[i]);
       }
       sctModel_.fireTableRowsDeleted(rows[0], rows[rows.length - 1]);
       sctModel_.fireTableDataChanged();
    }//GEN-LAST:event_removeButton_ActionPerformed

    private void loadButton_ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_loadButton_ActionPerformed
       File f = FileDialogs.openFile(this, "Load ShortCuts", MM_HOTKEYS);
       
       if (f != null && f.canRead()) {
         try {
            HotKeys.load(f);
            readKeys();
            sctModel_.fireTableDataChanged();
         } catch (FileNotFoundException ex) {
            ReportingUtils.showError("Could not find the file");
         }
       }

    }//GEN-LAST:event_loadButton_ActionPerformed

    private void saveButton_ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveButton_ActionPerformed
      generateKeys();
      try {
         File f = FileDialogs.save(this, "Save Shortcuts", MM_HOTKEYS);
         if (f == null) {
            return;
         }
         f.createNewFile();
         try {
            HotKeys.save(f);
         } catch (FileNotFoundException ex) {
            ReportingUtils.showError("File not found");
         }
      } catch (IOException ex) {
         ReportingUtils.showError("Failed to create file");
      }
    }//GEN-LAST:event_saveButton_ActionPerformed

 

   // Variables declaration - do not modify//GEN-BEGIN:variables
   private javax.swing.JButton addButton_;
   private javax.swing.JTable hotKeyTable_;
   private javax.swing.JScrollPane jScrollPane1_;
   private javax.swing.JButton loadButton_;
   private javax.swing.JButton removeButton_;
   private javax.swing.JButton saveButton_;
   // End of variables declaration//GEN-END:variables

}
