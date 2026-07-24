/**
 * The domain layer: one engine per command, plus the shared internals they sit on —
 * file semantics (YAML loading, charset detection), episode metadata, discovery,
 * probing, substitution, consistency checks. Everything here is free of CLI plumbing
 * and of presentation: engines take their inputs explicitly, return a result model, and
 * leave rendering to the `out` and `cli` layers.
 */
package org.plukh.mkvtool.core
