#!/usr/bin/env python3
"""Resolve and inspect actual APK service resources, including optimized release paths."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

VARIANTS = ('debug', 'release', 'visualAcceptance')
BASE = 'res/xml/agent_accessibility_service.xml'
V34 = 'res/xml-v34/agent_accessibility_service.xml'
SERVICE = 'dev.agentos.app.AgentAccessibilityService'
RESOURCE = 'xml/agent_accessibility_service'
ANDROID = r'(?:android:|http://schemas\.android\.com/apk/res/android:)'


def require(condition, message):
    if not condition: raise ValueError(message)


def attribute_values(xml, name):
    return re.findall(r'(?m)^\s*A: ' + ANDROID + re.escape(name) + r'\([^)]*\)=(.*)$', xml)


def boolean_attribute(xml, name, required=False):
    values = attribute_values(xml, name)
    require(len(values) <= 1 and (values or not required), 'Missing or duplicate boolean attribute: ' + name)
    if not values: return False
    value = values[0].strip()
    require(value in ('true', 'false', '(type 0x12)0xffffffff', '(type 0x12)0x0'), 'Non-boolean service attribute: ' + name)
    return value in ('true', '(type 0x12)0xffffffff')


def service_xml(xml, capture):
    require(re.search(r'(?m)^\s*E: accessibility-service\b', xml) is not None, 'Wrong XML root')
    require(boolean_attribute(xml, 'canTakeScreenshot') is capture, 'Unexpected screenshot capability')
    require(not boolean_attribute(xml, 'canPerformGestures'), 'Gesture capability must stay disabled')
    require(not boolean_attribute(xml, 'isAccessibilityTool', required=True), 'Service must not claim accessibility-tool status')
    require(boolean_attribute(xml, 'canRetrieveWindowContent', required=True), 'Existing node capability was lost')
    return {'canTakeScreenshot': capture, 'canPerformGestures': False, 'isAccessibilityTool': False}


def xml_elements(xml):
    """Parse only aapt2's element hierarchy and immediate attribute lines, without XML evaluation."""
    roots = []; stack = []
    for line in xml.splitlines():
        match = re.match(r'^(\s*)E: ([A-Za-z][A-Za-z0-9_-]*)(?:\s|$)', line)
        if match:
            depth = len(match.group(1))
            while stack and stack[-1][0] >= depth: stack.pop()
            node = {'tag': match.group(2), 'attributes': [], 'children': []}
            (stack[-1][1]['children'] if stack else roots).append(node)
            stack.append((depth, node))
        elif re.match(r'^\s*A:', line):
            require(bool(stack), 'Attribute without an XML element')
            stack[-1][1]['attributes'].append(line)
    return roots


def descendants(nodes, tag):
    found = []
    for node in nodes:
        if node['tag'] == tag: found.append(node)
        found.extend(descendants(node['children'], tag))
    return found


def attribute(node, name):
    values = attribute_values('\n'.join(node['attributes']), name)
    require(len(values) == 1, 'Missing or duplicate XML attribute: ' + name)
    return values[0].strip()


def string_attribute(node, name):
    value = attribute(node, name)
    match = re.fullmatch(r'"([^"\r\n]*)"(?: \(Raw: "\1"\))?', value)
    require(match is not None, 'Unexpected XML string attribute: ' + name)
    return match.group(1)


def instrumentation(xml):
    nodes = descendants(xml_elements(xml), 'instrumentation')
    require(len(nodes) == 1, 'Test APK must have exactly one actual instrumentation')
    result = {'runner': string_attribute(nodes[0], 'name'), 'targetPackage': string_attribute(nodes[0], 'targetPackage')}
    require(result == {'runner': 'androidx.test.runner.AndroidJUnitRunner', 'targetPackage': 'dev.agentos.app'}, 'Test APK targets another application')
    return result


def resource_entries(resources, variant):
    lines = resources.splitlines(); headers = []
    for index, line in enumerate(lines):
        match = re.match(r'^\s*resource (0x[0-9a-f]{8}) ([^\s]+)(?:\s.*)?$', line)
        if match and match.group(2) == RESOURCE: headers.append((index, match.group(1)))
    require(len(headers) == 1, 'Expected exactly one named accessibility-service XML resource')
    index, resource_id = headers[0]; entries = {}
    for line in lines[index + 1:]:
        if re.match(r'^\s*(?:resource |type |Package )', line): break
        if not line.strip(): continue
        match = re.fullmatch(r'\s*\(([^)]*)\) \(file\) (res/[A-Za-z0-9_./-]+\.xml) type=XML\s*', line)
        require(match is not None, 'Unsupported accessibility-service resource entry')
        config, path = match.groups()
        require(config not in entries and valid_resource_path(path), 'Duplicate or unsafe service resource entry')
        entries[config] = path
    expected = {'', 'v34'} if variant == 'visualAcceptance' else {''}
    require(set(entries) == expected and len(set(entries.values())) == len(entries), 'Unexpected accessibility-service resource configurations')
    return resource_id, {BASE if config == '' else V34: path for config, path in entries.items()}


def valid_resource_path(path):
    return (isinstance(path, str) and 0 < len(path) <= 180 and re.fullmatch(r'res/[A-Za-z0-9_./-]+\.xml', path) is not None
            and all(part not in ('', '.', '..') for part in path.split('/')))


def service_binding(xml, resource_id):
    nodes = descendants(xml_elements(xml), 'service')
    targets = [node for node in nodes if string_attribute(node, 'name') in (SERVICE, '.AgentAccessibilityService')]
    require(len(targets) == 1, 'Missing or duplicate production accessibility service')
    require(string_attribute(targets[0], 'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE', 'Wrong accessibility service permission')
    metadata = [node for node in targets[0]['children'] if node['tag'] == 'meta-data'
                and string_attribute(node, 'name') == 'android.accessibilityservice']
    require(len(metadata) == 1 and attribute(metadata[0], 'resource') == '@' + resource_id,
            'Production service does not reference the verified XML resource')
    return {'service': SERVICE, 'metadata': 'android.accessibilityservice', 'resource': RESOURCE, 'resourceId': resource_id}


def inspect(apk, aapt2, variant, test=False):
    require(apk.is_file() and not apk.is_symlink(), 'Missing/linked APK')
    def dump(kind, path=None):
        command = [str(aapt2), 'dump', kind, str(apk)]
        if path is not None: command += ['--file', path]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=True)
        require(len(result.stdout) <= 4 * 1024 * 1024, 'APK metadata dump is too large')
        return result.stdout.decode('utf-8', 'strict')
    record = {'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size}
    manifest = dump('xmltree', 'AndroidManifest.xml')
    if test:
        record['instrumentation'] = instrumentation(manifest)
    else:
        resource_id, paths = resource_entries(dump('resources'), variant)
        record['serviceBinding'] = service_binding(manifest, resource_id)
        with zipfile.ZipFile(apk) as archive:
            entries = archive.namelist()
            require(all(entries.count(path) == 1 for path in paths.values()), 'Resolved service XML is absent or duplicated in APK')
        record['resourcePaths'] = paths
        record['serviceResources'] = {canonical: service_xml(dump('xmltree', actual), canonical == V34)
                                      for canonical, actual in paths.items()}
    return record


def validate_report(value, source_sha):
    require(isinstance(value, dict) and value.get('schemaVersion') == 1 and value.get('kind') == 'visual-acceptance-apk-permissions'
            and value.get('sourceSha') == source_sha, 'Foreign APK permission proof')
    variants = value.get('variants', {})
    require(set(variants) == {'debug', 'release', 'visualAcceptance', 'debugAndroidTest', 'visualAcceptanceAndroidTest'}, 'Missing build variants')
    for name, record in variants.items():
        require(isinstance(record, dict) and type(record.get('bytes')) is int and record['bytes'] > 0
                and re.fullmatch(r'[0-9a-f]{64}', str(record.get('sha256', ''))) is not None, 'Invalid APK identity')
        if name.endswith('AndroidTest'):
            require(record.get('instrumentation') == {'runner': 'androidx.test.runner.AndroidJUnitRunner', 'targetPackage': 'dev.agentos.app'}, 'Wrong target instrumentation')
        else:
            expected = {BASE: {'canTakeScreenshot': False, 'canPerformGestures': False, 'isAccessibilityTool': False}}
            if name == 'visualAcceptance': expected[V34] = {'canTakeScreenshot': True, 'canPerformGestures': False, 'isAccessibilityTool': False}
            actual = record.get('serviceResources', {})
            require(actual == expected and all(type(value) is bool for flags in actual.values() for value in flags.values()),
                    'Normal builds or low APIs gained capture capability')
            paths = record.get('resourcePaths', {})
            require(isinstance(paths, dict) and set(paths) == set(expected) and all(valid_resource_path(path) for path in paths.values())
                    and len(set(paths.values())) == len(paths), 'Invalid resolved resource paths')
            binding = record.get('serviceBinding', {})
            require(isinstance(binding, dict) and set(binding) == {'service', 'metadata', 'resource', 'resourceId'}
                    and binding['service'] == SERVICE and binding['metadata'] == 'android.accessibilityservice'
                    and binding['resource'] == RESOURCE and re.fullmatch(r'0x[0-9a-f]{8}', str(binding['resourceId'])) is not None,
                    'Invalid packaged service resource binding')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt2', type=Path, required=True); parser.add_argument('--source-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    for name in ('debug', 'release', 'visual-acceptance', 'debug-test', 'visual-acceptance-test'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args(); require(re.fullmatch(r'[0-9a-f]{40}', args.source_sha) is not None, 'Invalid source SHA')
    records = {name: inspect(path, args.aapt2, name) for name, path in
               [('debug', args.debug), ('release', args.release), ('visualAcceptance', args.visual_acceptance)]}
    records.update({name: inspect(path, args.aapt2, name, test=True) for name, path in
                    [('debugAndroidTest', args.debug_test), ('visualAcceptanceAndroidTest', args.visual_acceptance_test)]})
    report = validate_report({'schemaVersion': 1, 'kind': 'visual-acceptance-apk-permissions',
                              'sourceSha': args.source_sha, 'variants': records}, args.source_sha)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__': main()
