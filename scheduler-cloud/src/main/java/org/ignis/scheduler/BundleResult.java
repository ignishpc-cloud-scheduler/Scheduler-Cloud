package org.ignis.scheduler;

import java.util.List;

public record BundleResult(
  byte[] tarGz,
  List<LargeFile> largeFiles
) { }
