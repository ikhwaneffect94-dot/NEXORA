#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-photo-enhancer.py <PhotoEnhancer.kt>")

source = Path(sys.argv[1])
if not source.is_file():
    raise SystemExit(f"PhotoEnhancer source not found: {source}")

text = source.read_text(encoding="utf-8")
if "override fun onCancelled(statusCode: Int)" in text:
    print("PhotoEnhancer cancellation callback already present.")
    raise SystemExit(0)

old = """                    override fun onSurfaceProcessed(timestamp: Long) = Unit
                })"""
new = """                    override fun onSurfaceProcessed(timestamp: Long) = Unit

                    // Media Enhancement beta05+ can cancel an in-flight processing request.
                    // Convert it to a normal failure so the local fallback can be used.
                    override fun onCancelled(statusCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Media Enhancement was cancelled with status $statusCode")
                            )
                        }
                    }
                })"""

if old not in text:
    raise SystemExit("Expected EnhancementCallback block not found; refusing to patch an unknown source version.")

source.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Added EnhancementCallback.onCancelled(statusCode) implementation.")
