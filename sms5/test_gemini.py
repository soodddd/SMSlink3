#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Test Gemini API availability"""

import sys
import os

# Fix Windows console encoding
if sys.platform == 'win32':
    os.system('chcp 65001 > nul')

try:
    from google import genai
except ImportError:
    print("ERROR: google-genai not installed")
    print("Run: pip install google-genai")
    sys.exit(1)

# Configure API key
API_KEY = "AIzaSyCnartpSEkgD0PnWcUlKoEZh9RiB54O4lA"

print("Testing Gemini API connection...")
print("=" * 50)

# Test different model versions
models_to_test = [
    "gemini-3.0-pro",
    "gemini-2.0-flash-exp",
    "gemini-2.0-pro",
    "gemini-1.5-pro",
    "gemini-1.5-flash",
]

available_models = []

for model_name in models_to_test:
    try:
        print(f"\nTesting model: {model_name}")
        client = genai.Client(api_key=API_KEY)
        response = client.models.generate_content(
            model=model_name,
            contents="Hello, respond with 'OK' if you can read this."
        )
        print(f"  [OK] {model_name} available")
        print(f"  Response: {response.text[:100]}")
        available_models.append(model_name)
        break  # Found working model, stop testing
    except Exception as e:
        error_msg = str(e)[:150]
        print(f"  [FAIL] {model_name}: {error_msg}")

print("\n" + "=" * 50)
if available_models:
    print(f"SUCCESS: Available models: {available_models}")
    print(f"\nRecommended model: {available_models[0]}")
else:
    print("ERROR: No available models found")
    print("\nTrying to list all available models...")
    try:
        client = genai.Client(api_key=API_KEY)
        models = client.models.list()
        print("Available models:")
        for model in models:
            print(f"  - {model.name}")
    except Exception as e:
        print(f"Failed to list models: {e}")
