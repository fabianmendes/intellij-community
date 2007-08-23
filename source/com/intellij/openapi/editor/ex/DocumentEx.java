package com.intellij.openapi.editor.ex;


import com.intellij.openapi.editor.Document;

public interface DocumentEx extends Document {
  void stripTrailingSpaces(boolean inChangedLinesOnly);
  void setStripTrailingSpacesEnabled(boolean isEnabled);

  LineIterator createLineIterator();

  void setModificationStamp(long modificationStamp);

  void addEditReadOnlyListener(EditReadOnlyListener listener);

  void removeEditReadOnlyListener(EditReadOnlyListener listener);

  void replaceText(CharSequence chars, long newModificationStamp);

  int getListenersCount();

  void suppressGuardedExceptions();
  void unSuppressGuardedExceptions();

  boolean isInEventsHandling();

  void clearLineModificationFlags();


  void removeRangeMarker(RangeMarkerEx rangeMarker);
  void addRangeMarker(RangeMarkerEx rangeMarker);

  boolean isInBulkUpdate();

  void setInBulkUpdate(boolean value);
}



