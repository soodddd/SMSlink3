#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Test Gemini 3 Pro specifically"""

from google import genai

API_KEY = "AIzaSyCnartpSEkgD0PnWcUlKoEZh9RiB54O4lA"

print("Testing Gemini 3 Pro...")
print("=" * 50)

models_to_test = [
    "gemini-3.1-pro-preview",
    "gemini-3-pro-preview",
    "gemini-2.5-pro"
]

for model_name in models_to_test:
    try:
        print(f"\nTesting: {model_name}")
        client = genai.Client(api_key=API_KEY)

        # Test code review capability
        response = client.models.generate_content(
            model=model_name,
            contents="""Review this Android code for issues:

fun sendSms(number: String, message: String) {
    val smsManager = SmsManager.getDefault()
    smsManager.sendTextMessage(number, null, message, null, null)
}

Respond in JSON format with any issues found."""
        )

        print(f"  [SUCCESS] {model_name} works!")
        print(f"  Response preview: {response.text[:200]}...")
        print(f"\n  Recommended model: {model_name}")
        break

    except Exception as e:
        print(f"  [FAIL] {model_name}: {str(e)[:100]}")

print("\n" + "=" * 50)
