#!/usr/bin/env python3
"""Verify signed standalone release APKs, then give the installable files short names.

Requires the Android SDK (aapt, zipalign, apksigner). Never signs with a new key.
Used by the release workflow before publishing its artifact.
"""
from pathlib import Path
from zipfile import ZipFile
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="app/build/outputs/apk/release")
    parser.add_argument("--output", default="dist")
    parser.add_argument("--application-id", help="expected package, defaults to gradle.properties or the main package")
    parser.add_argument("--certificate", help="optional expected SHA-256 certificate digest")
    args = parser.parse_args()
    if args.application_id is None:
        properties = Path("gradle.properties")
        custom = re.search(r"^bgfnApplicationId=(\S+)\s*$", properties.read_text(), re.M) if properties.exists() else None
        args.application_id = custom.group(1) if custom else "com.bgeforce.tv.main"
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise SystemExit("Set ANDROID_SDK_ROOT to your Android SDK.")
    tools = Path(sdk) / "build-tools" / "35.0.0"
    source, output = Path(args.source).resolve(), Path(args.output).resolve()
    if source == output:
        raise SystemExit("Use different source and output directories.")
    unsigned = list(source.glob("*-unsigned.apk"))
    if unsigned:
        raise SystemExit("Unsigned APK found. Run the signed release workflow with your fixed key.")
    apks = sorted(source.glob("*-release.apk"))
    if not apks:
        raise SystemExit("No signed release APKs found.")

    def run(*command):
        result = subprocess.run([str(p) for p in command], text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout + result.stderr

    labels = {"armeabi-v7a": "ARMv7", "arm64-v8a": "ARM64", "x86_64": "x86_64", "universal": "Universal"}
    certificate, records, used_names = args.certificate, [], set()
    # Verify the entire set before creating any installable output.
    for apk in apks:
        signature = run(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
        digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signature)
        if not digest or "Verified using v1 scheme (JAR signing): true" not in signature:
            raise RuntimeError(f"Missing valid Android 6 signature: {apk.name}")
        v2 = run(tools / "apksigner", "verify", "--verbose", "--min-sdk-version", "24", "--max-sdk-version", "28", apk)
        if "Verified using v2 scheme (APK Signature Scheme v2): true" not in v2:
            raise RuntimeError(f"Missing valid v2 signature: {apk.name}")
        certificate = certificate or digest.group(1)
        if digest.group(1) != certificate:
            raise RuntimeError(f"Different signing certificate: {apk.name}")
        run(tools / "zipalign", "-c", "-p", "4", apk)
        badging = run(tools / "aapt", "dump", "badging", apk)
        package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not package or package.group(1) != args.application_id:
            raise RuntimeError(f"Unexpected package name: {apk.name}")
        if "sdkVersion:'23'" not in badging or "application-debuggable" in badging:
            raise RuntimeError(f"Unexpected minimum SDK or debuggable release: {apk.name}")
        manifest = run(tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
        if "split='" in badging.splitlines()[0] or "E: uses-split" in manifest or "isSplitRequired" in manifest:
            raise RuntimeError(f"An incomplete split APK cannot be installed alone: {apk.name}")
        abi = next((name for name in labels if apk.name == f"app-{name}-release.apk"), None)
        if abi is None:
            raise RuntimeError(f"Unrecognized ABI filename: {apk.name}")
        with ZipFile(apk) as z:
            if z.testzip() is not None:
                raise RuntimeError(f"Corrupt ZIP: {apk.name}")
            for name in ("AndroidManifest.xml", "classes.dex", "resources.arsc", "assets/stream.html"):
                z.getinfo(name)
            actual_abis = {name.split("/")[1] for name in z.namelist() if name.startswith("lib/") and name.endswith(".so")}
            expected_abis = {"armeabi-v7a", "arm64-v8a", "x86_64"} if abi == "universal" else {abi}
            if actual_abis != expected_abis:
                raise RuntimeError(f"Unexpected native libraries: {apk.name}: {actual_abis}")
            for name in z.namelist():
                if name.endswith(".dex") and z.read(name)[:8] != b"dex\n035\0":
                    raise RuntimeError(f"Unexpected DEX version for Android 6: {name}")
        name = f"BGFN-TV-{package.group(2)}-{labels[abi]}.apk"
        if name in used_names:
            raise RuntimeError(f"Duplicate output: {name}")
        used_names.add(name)
        records.append({"source": apk, "filename": name, "version_code": int(package.group(2)), "version_name": package.group(3), "abi": abi})
    if len({(r['version_code'], r['version_name']) for r in records}) != 1:
        raise RuntimeError("Mixed versions in the release directory.")
    output.mkdir(parents=True, exist_ok=True)
    # Dedicated output directory: reject stale installables instead of uploading mixed versions.
    if any(output.glob("*.apk")):
        raise RuntimeError("Output already contains APKs. Choose an empty directory.")
    for record in records:
        src = record.pop("source")
        dest = output / record["filename"]
        shutil.copy2(src, dest)
        record["bytes"] = dest.stat().st_size
        record["sha256"] = hashlib.sha256(dest.read_bytes()).hexdigest()
        record["certificate_sha256"] = certificate
    (output / "APKS-VERIFICADOS.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text("".join(r['sha256'] + "  " + r['filename'] + "\n" for r in records))
    (output / "LEIA-ANTES-DE-INSTALAR.txt").write_text(
        "BGeforce Now TV\n\n"
        "Extraia o ZIP baixado do GitHub. Instale o arquivo terminado em .apk.\n"
        "ARMv7 = Android ARM de 32 bits; ARM64 = Android ARM de 64 bits.\n"
        "Universal inclui as duas arquiteturas e x86_64, mas ocupa mais espaco.\n"
        "Nao renomeie um ZIP, TXT, AAB ou APK unsigned para .apk.\n"
        "Atualize por cima do app principal, usando o mesmo pacote e chave para manter os dados.\n"
        "Se houver erro de analise, confira o tamanho/SHA-256 apos transferir para a TV.\n"
        "Uma CPU de 64 bits pode usar Android de 32 bits: escolha conforme o sistema.\n"
        "Android minimo: 6.0 (API 23).\n",
        encoding="utf-8",
    )
    print(json.dumps({"status": "verified", "apks": records}, ensure_ascii=False))


if __name__ == "__main__":
    main()
