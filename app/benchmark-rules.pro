# The `benchmark` build type is release-like but must stay debuggable enough
# for Macrobenchmark to resolve method names in traces.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
