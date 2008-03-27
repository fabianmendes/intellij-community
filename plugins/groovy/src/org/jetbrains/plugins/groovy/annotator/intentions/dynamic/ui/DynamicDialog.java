package org.jetbrains.plugins.groovy.annotator.intentions.dynamic.ui;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.undo.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.*;
import com.intellij.ui.EditorComboBoxEditor;
import com.intellij.ui.EditorTextField;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyBundle;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.annotator.intentions.QuickfixUtil;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.DClassElement;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.DynamicManager;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.MyPair;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.elements.DItemElement;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.elements.DMethodElement;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.elements.DPropertyElement;
import org.jetbrains.plugins.groovy.codeInspection.GroovyInspectionBundle;
import org.jetbrains.plugins.groovy.debugger.fragments.GroovyCodeFragment;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.expectedTypes.GroovyExpectedTypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.expectedTypes.TypeConstraint;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.EventListenerList;
import java.awt.*;
import java.awt.event.*;
import java.util.EventListener;
import java.util.List;

/**
 * User: Dmitry.Krasilschikov
 * Date: 18.12.2007
 */
public abstract class DynamicDialog extends DialogWrapper {
  private JComboBox myClassComboBox;
  private JPanel myPanel;
  private JComboBox myTypeComboBox;
  private JLabel myClassLabel;
  private JLabel myTypeLabel;
  private JPanel myTypeStatusPanel;
  private JLabel myTypeStatusLabel;
  private JTable myParametersTable;
  private JLabel myTableLabel;
  private final DynamicManager myDynamicManager;
  private final Project myProject;
  private final DItemElement myItemElement;
  private EventListenerList myListenerList = new EventListenerList();
  private final GrReferenceExpression myReferenceExpression;


  public DynamicDialog(GrReferenceExpression referenceExpression) {
    super(referenceExpression.getProject(), true);
    myProject = referenceExpression.getProject();

    if (!isTableVisible()) {
      myParametersTable.setVisible(false);
      myTableLabel.setVisible(false);
    }
    myReferenceExpression = referenceExpression;
    setTitle(GroovyInspectionBundle.message("dynamic.element"));
    myDynamicManager = DynamicManager.getInstance(myProject);

    init();

    setUpTypeComboBox();
    setUpContainingClassComboBox();
    setUpStatusLabel();
    setUpTableNameLabel();

    final Border border2 = BorderFactory.createLineBorder(Color.BLACK);
    myParametersTable.setBorder(border2);

    myTypeLabel.setLabelFor(myTypeComboBox);
    myClassLabel.setLabelFor(myClassComboBox);

    myItemElement = createItemElement();
  }

  protected DItemElement createItemElement() {
    DItemElement myDynamicElement;
    if (QuickfixUtil.isCall(myReferenceExpression)) {
      final PsiType[] types = PsiUtil.getArgumentTypes(myReferenceExpression, false);
      final String[] names = QuickfixUtil.getMethodArgumentsNames(myProject, types);
      final List<MyPair> pairs = QuickfixUtil.swapArgumentsAndTypes(names, types);

      myDynamicElement = new DMethodElement(myReferenceExpression.getName(), null, pairs);
    } else {
      myDynamicElement = new DPropertyElement(myReferenceExpression.getName(), null);
    }
    return myDynamicElement;
  }

  private void setUpTableNameLabel() {
    myTableLabel.setLabelFor(myParametersTable);
    myTableLabel.setText(GroovyBundle.message("dynamic.properties.table.name"));
  }

  
  private void setUpStatusLabel() {
    if (!isTypeChekerPanelEnable()) {
      myTypeStatusPanel.setVisible(false);
      return;
    }
    myTypeStatusLabel.setHorizontalTextPosition(SwingConstants.RIGHT);

    final GrTypeElement typeElement = getEnteredTypeName();
    if (typeElement == null) {
      setStatusTextAndIcon(IconLoader.getIcon("/compiler/warning.png"), GroovyInspectionBundle.message("no.type.specified"));
      return;
    }

    final PsiType type = typeElement.getType();
    setStatusTextAndIcon(IconLoader.getIcon("/compiler/information.png"), GroovyInspectionBundle.message("resolved.type.status", type.getPresentableText()));
  }

  private void setStatusTextAndIcon(final Icon icon, final String text) {
    myTypeStatusLabel.setIcon(icon);
    myTypeStatusLabel.setText(text);
  }


  private void setUpContainingClassComboBox() {
    final String typeDefinition;

    final PsiClass targetClass = QuickfixUtil.findTargetClass(myReferenceExpression);

    if (targetClass == null) typeDefinition = "java.lang.Object";
    else typeDefinition = targetClass.getQualifiedName();

    final PsiClassType type = TypesUtil.createType(typeDefinition, myReferenceExpression);
    final PsiClass psiClass = type.resolve();

    if (psiClass == null) return;

    for (PsiClass aClass : PsiUtil.iterateSupers(psiClass, true)) {
      myClassComboBox.addItem(new ContainingClassItem(aClass));
    }

    myPanel.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        myClassComboBox.requestFocus();
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.ALT_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
  }

  private Document createDocument(final String text) {
    GroovyCodeFragment fragment = new GroovyCodeFragment(myProject, text);
    fragment.setContext(myReferenceExpression);
    return PsiDocumentManager.getInstance(myProject).getDocument(fragment);
  }

  private void setUpTypeComboBox() {
    final EditorComboBoxEditor comboEditor = new EditorComboBoxEditor(myProject, GroovyFileType.GROOVY_FILE_TYPE);

    final Document document = createDocument("");
    comboEditor.setItem(document);

    myTypeComboBox.setEditor(comboEditor);
    myTypeComboBox.setEditable(true);
    myTypeComboBox.grabFocus();

    addDataChangeListener();

    myTypeComboBox.addItemListener(
        new ItemListener() {
          public void itemStateChanged(ItemEvent e) {
            fireDataChanged();
          }
        }
    );

    myPanel.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        myTypeComboBox.requestFocus();
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.ALT_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);


    final EditorTextField editorTextField = (EditorTextField) myTypeComboBox.getEditor().getEditorComponent();

    editorTextField.addDocumentListener(new DocumentListener() {
      public void beforeDocumentChange(DocumentEvent event) {
      }

      public void documentChanged(DocumentEvent event) {
        fireDataChanged();
      }
    });

    final TypeConstraint[] constrants = GroovyExpectedTypesUtil.calculateTypeConstraints(myReferenceExpression);
    PsiType type = constrants.length == 1 ? constrants[0].getType() : TypesUtil.getJavaLangObject(myReferenceExpression);
    myTypeComboBox.getEditor().setItem(createDocument(type.getPresentableText()));
  }

  protected void addDataChangeListener() {
    myListenerList.add(DataChangedListener.class, new DataChangedListener());
  }

  class DataChangedListener implements EventListener {
    void dataChanged() {
      updateOkStatus();
    }
  }

  protected void updateOkStatus() {
    GrTypeElement typeElement = getEnteredTypeName();

    if (typeElement == null) {
      setOKActionEnabled(false);

    } else {
      setOKActionEnabled(true);

      final PsiType type = typeElement.getType();
      if (type instanceof PsiClassType && ((PsiClassType) type).resolve() == null) {
        setStatusTextAndIcon(IconLoader.getIcon("/compiler/warning.png"), GroovyInspectionBundle.message("unresolved.type.status", type.getPresentableText()));
      } else {
        setStatusTextAndIcon(IconLoader.getIcon("/compiler/information.png"), GroovyInspectionBundle.message("resolved.type.status", type.getPresentableText()));
      }
    }
  }

  @Nullable
  public GrTypeElement getEnteredTypeName() {
    final Document typeEditorDocument = getTypeEditorDocument();

    if (typeEditorDocument == null) return null;

    try {
      final String typeText = typeEditorDocument.getText();

      return GroovyPsiElementFactory.getInstance(myProject).createTypeElement(typeText);
    } catch (IncorrectOperationException e) {
      return null;
    }
  }

  public Document getTypeEditorDocument() {
    final Object item = myTypeComboBox.getEditor().getItem();

    return item instanceof Document ? (Document) item : null;

  }

  public ContainingClassItem getEnteredContaningClass() {
    final Object item = myClassComboBox.getSelectedItem();
    if (!(item instanceof ContainingClassItem)) return null;

    return ((ContainingClassItem) item);
  }

  protected void fireDataChanged() {
    Object[] list = myListenerList.getListenerList();
    for (Object aList : list) {
      if (aList instanceof DataChangedListener) {
        ((DataChangedListener) aList).dataChanged();
      }
    }
  }

  @Nullable
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  protected void doOKAction() {
    super.doOKAction();
    GrTypeElement typeElement = getEnteredTypeName();

    final DItemElement dynamicElement = getItemElement();

    if (typeElement == null) {
      dynamicElement.setType("java.lang.Object");
    } else {
      PsiType type = typeElement.getType();
      if (type instanceof PsiPrimitiveType) {
        type = TypesUtil.boxPrimitiveType(type, typeElement.getManager(), myProject.getAllScope());
      }

      final String typeQualifiedName = type.getCanonicalText();

      if (typeQualifiedName != null) {
        dynamicElement.setType(typeQualifiedName);
      } else {
        dynamicElement.setType(type.getPresentableText());
      }
    }

    final DynamicManager dynamicManager = DynamicManager.getInstance(myProject);
    final DClassElement classElement = dynamicManager.getOrCreateClassElement(myProject, getEnteredContaningClass().getContainingClass().getQualifiedName());
    final Document document = PsiDocumentManager.getInstance(myProject).getDocument(myReferenceExpression.getContainingFile());
    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        UndoManager.getInstance(myProject).undoableActionPerformed(new UndoableAction() {
          public void undo() throws UnexpectedUndoException {
            dynamicManager.removeItemElement(dynamicElement);

            myDynamicManager.fireChange();
          }

          public void redo() throws UnexpectedUndoException {
            addElement(dynamicElement, dynamicManager, classElement);
          }

          public DocumentReference[] getAffectedDocuments() {
            return new DocumentReference[]{DocumentReferenceByDocument.createDocumentReference(document)};
          }

          public boolean isComplex() {
            return true;
          }
        });

        addElement(dynamicElement, dynamicManager, classElement);
      }
    }, "Add dynamic element", null);
  }

  private void addElement(DItemElement dynamicElement, DynamicManager dynamicManager, DClassElement classElement) {
    if (dynamicElement instanceof DMethodElement) {
      dynamicManager.addMethod(classElement, ((DMethodElement) dynamicElement));
    } else {
      dynamicManager.addProperty(classElement, ((DPropertyElement) dynamicElement));
    }
    myDynamicManager.fireChange();
  }

  class ContainingClassItem {
    private final PsiClass myContainingClass;

    ContainingClassItem(PsiClass containingClass) {
      myContainingClass = containingClass;
    }

    public String toString() {
      return myContainingClass.getName();
    }

    public PsiClass getContainingClass() {
      return myContainingClass;
    }
  }

  class TypeItem {
    private final PsiType myPsiType;

    TypeItem(PsiType psiType) {
      myPsiType = psiType;
    }

    public String toString() {
      return myPsiType.getPresentableText();
    }

    @NotNull
    String getPresentableText() {
      return myPsiType.getPresentableText();
    }
  }

  
  public void doCancelAction() {
    super.doCancelAction();

    DaemonCodeAnalyzer.getInstance(myProject).restart();
  }

  public JComponent getPreferredFocusedComponent() {
    return myTypeComboBox;
  }

  protected JPanel getPanel() {
    return myPanel;
  }

  protected boolean isTableVisible() {
    return false;
  }

  public JTable getParametersTable() {
    return myParametersTable;
  }

  protected boolean isTypeChekerPanelEnable() {
    return false;
  }

  public Project getProject() {
    return myProject;
  }

  protected void setUpTypeLabel(String typeLabelText) {
    myTypeLabel.setText(typeLabelText);
  }

  public DItemElement getItemElement() {
    return myItemElement;
  }
}