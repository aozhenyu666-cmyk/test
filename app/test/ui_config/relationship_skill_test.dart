// The dsh-go-relationship skill: a prebuilt workflow shipped in the app's
// assets. It is installed into `$DSH_HOME/skills/` like the other managed
// skills, so the phone-side model follows prepared steps and calls the
// prebuilt relationship tools instead of writing code.

import 'dart:convert';
import 'dart:io';

import 'package:dsh_app/ui_config/ui_config.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late UiSkillBundle bundle;
  late Directory root;

  setUpAll(() async => bundle = await loadUiSkillBundle(readAssetFromDisk, relationshipSkillName));
  setUp(() => root = tempRootfs());
  tearDown(() {
    if (root.existsSync()) root.deleteSync(recursive: true);
  });

  Directory installed() => Directory('${root.path}/root/.dsh/skills/$relationshipSkillName');

  test('the bundle is managed, named, and SKILL.md is written last', () {
    final assets = Directory('$appDir/assets/skills/$relationshipSkillName');
    final manifest = jsonDecode(File('${assets.path}/manifest.json').readAsStringSync()) as Map;
    expect(manifest['name'], relationshipSkillName);
    expect(bundle.name, relationshipSkillName);
    expect(bundle.files.keys, {'SKILL.md'});
    expect(bundle.writeOrder.last.key, 'SKILL.md');
    expect(bundle.skillMd.split('\n').first, '---');
    expect(isManagedUiSkill(bundle.skillMd), isTrue);
    expect(bundle.skillMd, contains('name: $relationshipSkillName\n'));
    expect(bundle.skillMd, contains('relationship_check'));
    expect(bundle.skillMd, contains('mcp__phone__now'));
  });

  test('installs and keeps a managed copy current, then leaves a taken-over one alone', () async {
    expect(await installUiSkill(root, bundle), UiSkillInstallResult.installed);
    expect(File('${installed().path}/SKILL.md').readAsStringSync(), bundle.skillMd);
    expect(await installUiSkill(root, bundle), UiSkillInstallResult.unchanged);

    final skill = File('${installed().path}/SKILL.md');
    skill.writeAsStringSync(
      skill.readAsStringSync().replaceFirst(RegExp(r'^# dsh-go:managed.*\n', multiLine: true), ''),
    );
    skill.writeAsStringSync('${skill.readAsStringSync()}\n我的版本');
    expect(await installUiSkill(root, bundle), UiSkillInstallResult.userOwned);
    expect(skill.readAsStringSync(), contains('我的版本'));
  });
}
