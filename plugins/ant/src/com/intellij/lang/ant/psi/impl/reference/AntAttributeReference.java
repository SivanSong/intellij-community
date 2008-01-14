package com.intellij.lang.ant.psi.impl.reference;

import com.intellij.lang.ant.psi.AntStructuredElement;
import com.intellij.lang.ant.psi.introspection.AntTypeDefinition;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

/**
 * Ant attribute reference serves only for completion.
 */
public class AntAttributeReference extends AntGenericReference {

  public AntAttributeReference(final AntStructuredElement element,
                               final String str,
                               final TextRange textRange,
                               final XmlAttribute attribute) {
    super(element, str, textRange, attribute);
  }

  public AntStructuredElement getElement() {
    return (AntStructuredElement)super.getElement();
  }

  public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
    return null;
  }

  public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
    return null;
  }

  public PsiElement resolve() {
    return null;
  }

  public Object[] getVariants() {
    final AntTypeDefinition def = getElement().getTypeDefinition();
    return (def == null) ? EMPTY_ARRAY : def.getAttributes();
  }


  public boolean shouldBeSkippedByAnnotator() {
    return true;
  }
}
