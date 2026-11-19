import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// Atomic serialized storage in `%APPDATA%\dsh_go_fish_pc` for secrets, rules,
/// usage ledgers, AI config, focus lock state, and native watchdog coordination.
class FileSecretStore {
  FileSecretStore([Directory? rootDir]) : _dir = rootDir ?? _defaultDir();

  final Directory _dir;
  Future<void> _writeQueue = Future<void>.value();

  String get rootPath => _dir.path;

  static Directory _defaultDir() {
    final appData = Platform.environment['APPDATA'] ??
        Platform.environment['LOCALAPPDATA'] ??
        Directory.systemTemp.path;
    return Directory('$appData${Platform.pathSeparator}dsh_go_fish_pc');
  }

  File _file(String name) => File('${_dir.path}${Platform.pathSeparator}$name');

  File get _secretsFile => _file('remote_secrets.json');
  File get _authorizedExitFlag => _file('authorized_exit.flag');
  File get _watchdogLogFile => _file('watchdog_restarts.log');

  Future<T> _enqueue<T>(Future<T> Function() action) {
    final completer = Completer<T>();
    _writeQueue = _writeQueue.then((_) async {
      try {
        completer.complete(await action());
      } catch (e, st) {
        completer.completeError(e, st);
      }
    });
    return completer.future;
  }

  Future<void> _writeAtomic(File target, String content) async {
    await _dir.create(recursive: true);
    final tmp = File(
      '${target.path}.tmp.$pid.${DateTime.now().microsecondsSinceEpoch}',
    );
    await tmp.writeAsString(content, flush: true);
    try {
      await tmp.rename(target.path);
    } on FileSystemException {
      await target.writeAsString(content, flush: true);
      if (await tmp.exists()) {
        try {
          await tmp.delete();
        } catch (_) {}
      }
    }
  }

  Future<Map<String, String>> _readAllSecrets() async {
    if (!await _secretsFile.exists()) return <String, String>{};
    try {
      final raw = jsonDecode(await _secretsFile.readAsString());
      if (raw is! Map) return <String, String>{};
      return {
        for (final e in raw.entries)
          if (e.key is String && e.value is String)
            e.key as String: e.value as String,
      };
    } catch (_) {
      return <String, String>{};
    }
  }

  Future<String?> readSecret(String key) async =>
      (await _readAllSecrets())[key];

  Future<void> writeSecret(String key, String value) => _enqueue(() async {
        final all = await _readAllSecrets();
        all[key] = value;
        await _writeAtomic(_secretsFile, jsonEncode(all));
      });

  Future<void> deleteSecret(String key) => _enqueue(() async {
        final all = await _readAllSecrets();
        all.remove(key);
        await _writeAtomic(_secretsFile, jsonEncode(all));
      });

  Future<Map<String, Object?>> readJsonFile(String name) async {
    final target = _file(name);
    if (!await target.exists()) return <String, Object?>{};
    try {
      final raw = jsonDecode(await target.readAsString());
      return raw is Map ? raw.cast<String, Object?>() : <String, Object?>{};
    } catch (_) {
      return <String, Object?>{};
    }
  }

  Future<void> writeJsonFile(String name, Map<String, Object?> map) =>
      _enqueue(() async {
        await _writeAtomic(_file(name), jsonEncode(map));
      });

  Future<void> writeAuthorizedExitFlag(String reason) => _enqueue(() async {
        await _writeAtomic(
          _authorizedExitFlag,
          'authorized_at=${DateTime.now().toUtc().millisecondsSinceEpoch}\nreason=$reason\n',
        );
      });

  Future<void> clearAuthorizedExitFlag() => _enqueue(() async {
        if (await _authorizedExitFlag.exists()) {
          try {
            await _authorizedExitFlag.delete();
          } catch (_) {}
        }
      });

  Future<List<({int atUtcMs, String detail})>> consumeWatchdogRestartLog() =>
      _enqueue(() async {
        if (!await _watchdogLogFile.exists()) {
          return const <({int atUtcMs, String detail})>[];
        }
        try {
          final lines = await _watchdogLogFile.readAsLines();
          await _watchdogLogFile.writeAsString('', flush: true);
          final out = <({int atUtcMs, String detail})>[];
          for (final rawLine in lines) {
            final line = rawLine.trim();
            if (line.isEmpty) continue;
            final sep = line.indexOf('|');
            if (sep <= 0) {
              out.add((
                atUtcMs: DateTime.now().toUtc().millisecondsSinceEpoch,
                detail: line,
              ));
            } else {
              final ts = int.tryParse(line.substring(0, sep)) ??
                  DateTime.now().toUtc().millisecondsSinceEpoch;
              final detail = line.substring(sep + 1).trim();
              out.add((atUtcMs: ts, detail: detail));
            }
          }
          return out;
        } catch (_) {
          return const <({int atUtcMs, String detail})>[];
        }
      });
}
