import 'dart:convert';
import 'dart:io';

import 'package:dsh_remote/dsh_remote.dart';

/// Atomic file-backed [SecretStore] & settings storage in `%APPDATA%\dsh_go_fish_pc`.
class FileSecretStore implements SecretStore {
  FileSecretStore([Directory? rootDir]) : _dir = rootDir ?? _defaultDir();

  final Directory _dir;

  static Directory _defaultDir() {
    final appData = Platform.environment['APPDATA'] ??
        Platform.environment['LOCALAPPDATA'] ??
        Directory.systemTemp.path;
    return Directory('$appData${Platform.pathSeparator}dsh_go_fish_pc');
  }

  File get _secretsFile => File('${_dir.path}${Platform.pathSeparator}remote_secrets.json');
  File get _prefsFile => File('${_dir.path}${Platform.pathSeparator}pc_prefs.json');

  Future<Map<String, String>> _readAllSecrets() async {
    if (!await _secretsFile.exists()) return <String, String>{};
    try {
      final raw = jsonDecode(await _secretsFile.readAsString());
      if (raw is! Map) return <String, String>{};
      return {
        for (final e in raw.entries)
          if (e.key is String && e.value is String) e.key as String: e.value as String,
      };
    } catch (_) {
      return <String, String>{};
    }
  }

  Future<void> _writeAtomic(File target, String content) async {
    await _dir.create(recursive: true);
    final tmp = File('${target.path}.tmp.$pid');
    await tmp.writeAsString(content, flush: true);
    await tmp.rename(target.path);
  }

  @override
  Future<String?> read(String key) async => (await _readAllSecrets())[key];

  @override
  Future<void> write(String key, String value) async {
    final all = await _readAllSecrets();
    all[key] = value;
    await _writeAtomic(_secretsFile, jsonEncode(all));
  }

  @override
  Future<void> delete(String key) async {
    final all = await _readAllSecrets();
    all.remove(key);
    await _writeAtomic(_secretsFile, jsonEncode(all));
  }

  Future<Map<String, Object?>> readPrefs() async {
    if (!await _prefsFile.exists()) return <String, Object?>{};
    try {
      final raw = jsonDecode(await _prefsFile.readAsString());
      return raw is Map ? raw.cast<String, Object?>() : <String, Object?>{};
    } catch (_) {
      return <String, Object?>{};
    }
  }

  Future<void> savePrefs(Map<String, Object?> prefs) async {
    await _writeAtomic(_prefsFile, jsonEncode(prefs));
  }
}
