package com.intellij.lang.ant.psi.impl.reference;

import com.intellij.lang.ant.psi.AntElement;
import com.intellij.lang.ant.psi.impl.*;
import com.intellij.lang.ant.psi.impl.reference.providers.*;
import com.intellij.psi.impl.source.resolve.reference.PsiReferenceProvider;
import com.intellij.util.containers.HashMap;

import java.util.Map;

public class AntReferenceProvidersRegistry {
  private static final Map<Class, PsiReferenceProvider[]> ourProviders;

  static {
    ourProviders = new HashMap<Class, PsiReferenceProvider[]>();

    final AntAttributeReferenceProvider attrProvider = new AntAttributeReferenceProvider();
    final AntElementNameReferenceProvider nameProvider = new AntElementNameReferenceProvider();
    final AntPropertyReferenceProvider propProvider = new AntPropertyReferenceProvider();
    final AntFileReferenceProvider fileProvider = new AntFileReferenceProvider();
    final AntRefIdReferenceProvider refIdProvider = new AntRefIdReferenceProvider();
    final AntMacroDefParameterReferenceProvider macroParamsProvider = new AntMacroDefParameterReferenceProvider();
    final AntSingleTargetReferenceProvider targetProvider = new AntSingleTargetReferenceProvider();

    ourProviders.put(AntProjectImpl.class, new PsiReferenceProvider[]{targetProvider, nameProvider, attrProvider});
    ourProviders.put(AntTargetImpl.class, new PsiReferenceProvider[]{new AntTargetListReferenceProvider(), propProvider, refIdProvider,
      nameProvider, attrProvider});
    ourProviders.put(AntStructuredElementImpl.class, new PsiReferenceProvider[]{fileProvider, propProvider, refIdProvider, nameProvider,
      attrProvider, macroParamsProvider});
    ourProviders.put(AntTimestampFormatImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntTaskImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntPropertyImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntMacroDefImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntPresetDefImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntTypeDefImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntImportImpl.class, new PsiReferenceProvider[]{fileProvider, propProvider, nameProvider, attrProvider});
    ourProviders.put(AntAntImpl.class, new PsiReferenceProvider[]{fileProvider, propProvider, nameProvider, attrProvider,
      targetProvider, macroParamsProvider});
    ourProviders.put(AntBuildNumberImpl.class,
                     new PsiReferenceProvider[]{fileProvider, propProvider, nameProvider, attrProvider, macroParamsProvider});
    ourProviders.put(AntCallImpl.class, new PsiReferenceProvider[]{targetProvider, propProvider, refIdProvider, nameProvider,
      attrProvider, macroParamsProvider});

    ourProviders.put(AntDirSetImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntFileListImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntFileSetImpl.class, ourProviders.get(AntStructuredElementImpl.class));
    ourProviders.put(AntPathImpl.class, ourProviders.get(AntStructuredElementImpl.class));
  }

  private AntReferenceProvidersRegistry() {
  }

  public static PsiReferenceProvider[] getProvidersByElement(final AntElement element) {
    PsiReferenceProvider[] result = ourProviders.get(element.getClass());
    return (result != null) ? result : PsiReferenceProvider.EMPTY_ARRAY;
  }
}
