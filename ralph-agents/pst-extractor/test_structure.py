#!/usr/bin/env python3
"""
Simple test script to verify the pst-extractor structure is working
"""
import os
import sys

# Add the current directory to Python path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

def test_imports():
    """Test that all modules can be imported."""
    try:
        from activity_guard import ActivityGuard
        from nas_api import SynologyAPI
        from db import Database
        from pst_reader import read_pst_file, get_pst_info
        from attachment_saver import save_attachments
        print("✅ All modules imported successfully")
        return True
    except Exception as e:
        print(f"❌ Import error: {e}")
        return False

def test_structure():
    """Test that all required files exist."""
    required_files = [
        'Dockerfile',
        'requirements.txt',
        'main.py',
        'pst_reader.py',
        'attachment_saver.py',
        'activity_guard.py',
        'nas_api.py',
        'db.py'
    ]

    missing_files = []
    for file in required_files:
        if not os.path.exists(file):
            missing_files.append(file)

    if missing_files:
        print(f"❌ Missing files: {missing_files}")
        return False
    else:
        print("✅ All required files present")
        return True

if __name__ == "__main__":
    print("Testing pst-extractor structure...")

    success = True
    success &= test_structure()
    success &= test_imports()

    if success:
        print("\n🎉 pst-extractor structure test PASSED")
        sys.exit(0)
    else:
        print("\n💥 pst-extractor structure test FAILED")
        sys.exit(1)