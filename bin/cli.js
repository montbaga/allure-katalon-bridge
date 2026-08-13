#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const packageRoot = path.join(__dirname, '..');
const payloadRoot = path.join(packageRoot, 'payload');
const version = fs.readFileSync(path.join(packageRoot, 'VERSION'), 'utf8').trim();

const CONFIG_REL = path.join('Include', 'config', 'allure', 'allure.properties');
const CATEGORIES_REL = path.join('Include', 'config', 'allure', 'categories.json');
const JARS_TO_REGISTER = [
    'Drivers/allure-java-commons-2.35.4.jar',
    'Drivers/allure-model-2.35.4.jar',
];
const CLEANUP_DIRS = ['Keywords/allure', 'Libs/allure', 'Include/config/allure', 'Test Listeners', 'Drivers'];

function usage() {
    console.log(`allure-katalon-bridge v${version}

Usage:
  allure-katalon-bridge install <projectPath> [--force]
  allure-katalon-bridge uninstall <projectPath> [--remove-config]

  install     Copies the bridge into a Katalon Studio project.
              --force also overwrites an existing allure.properties.
  uninstall   Removes a previously installed bridge, using the manifest
              install left behind. --remove-config also deletes
              allure.properties and categories.json.
`);
}

function findPrjFile(projectPath) {
    return fs.readdirSync(projectPath, { withFileTypes: true })
        .find((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.prj'));
}

function walkFiles(dir) {
    const results = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            results.push(...walkFiles(full));
        } else if (entry.isFile()) {
            results.push(full);
        }
    }
    return results;
}

// Mirrors the bridge's own install.ps1/install.sh: library/keyword/jar
// files are always refreshed; allure.properties is left alone if already
// customized, unless --force is passed.
function install(projectPath, force) {
    if (!fs.existsSync(projectPath)) {
        throw new Error(`Project path does not exist: ${projectPath}`);
    }
    projectPath = fs.realpathSync(projectPath);

    const prjFile = findPrjFile(projectPath);
    if (!prjFile) {
        throw new Error(`No *.prj file found directly under '${projectPath}'. This does not look like a Katalon Studio project root - aborting to avoid writing into the wrong folder.`);
    }

    console.log(`Installing Allure-Katalon Bridge v${version} into: ${projectPath}`);
    console.log(`  (detected Katalon project: ${prjFile.name})`);

    const manifestDir = path.join(projectPath, '.allure-bridge');
    fs.mkdirSync(manifestDir, { recursive: true });

    const installedFiles = [];
    for (const file of walkFiles(payloadRoot)) {
        const relativePath = path.relative(payloadRoot, file);
        const destPath = path.join(projectPath, relativePath);

        if (relativePath === CONFIG_REL && fs.existsSync(destPath) && !force) {
            console.log(`  SKIP (already customized, use --force to overwrite): ${relativePath}`);
            continue;
        }

        fs.mkdirSync(path.dirname(destPath), { recursive: true });
        fs.copyFileSync(file, destPath);
        if (/\.(sh|command)$/.test(relativePath)) {
            fs.chmodSync(destPath, 0o755);
        }
        installedFiles.push(relativePath);
        console.log(`  OK   ${relativePath}`);
    }

    fs.writeFileSync(path.join(manifestDir, 'manifest.txt'), [version, ...installedFiles].join('\n') + '\n', 'utf8');

    registerClasspathJars(projectPath);

    console.log('');
    console.log('Install complete.');
    console.log('Next steps:');
    console.log('  1. Reopen (or refresh) the project in Katalon Studio.');
    console.log('  2. Run any Test Suite as usual - no changes needed to existing tests.');
    console.log("  3. Look for '[Allure]' lines in the console, and an allure-results/ folder afterwards.");
    console.log('  4. View it: npm install -g allure-commandline; allure serve allure-results');
}

// Best-effort, like install.ps1's version: only touches .classpath if the
// project already has one open in the IDE, and only adds entries that
// aren't already there.
function registerClasspathJars(projectPath) {
    const classpathFile = path.join(projectPath, '.classpath');
    if (!fs.existsSync(classpathFile)) {
        return;
    }
    let xml = fs.readFileSync(classpathFile, 'utf8');
    if (!xml.includes('</classpath>')) {
        return;
    }
    let changed = false;
    for (const jarPath of JARS_TO_REGISTER) {
        if (!xml.includes(`path="${jarPath}"`)) {
            xml = xml.replace('</classpath>', `\t<classpathentry kind="lib" path="${jarPath}"/>\n</classpath>`);
            changed = true;
        }
    }
    if (changed) {
        fs.writeFileSync(classpathFile, xml, 'utf8');
        console.log('  OK   .classpath (registered Drivers jars for the IDE editor)');
    }
}

function uninstall(projectPath, removeConfig) {
    if (!fs.existsSync(projectPath)) {
        throw new Error(`Project path does not exist: ${projectPath}`);
    }
    projectPath = fs.realpathSync(projectPath);

    const manifestPath = path.join(projectPath, '.allure-bridge', 'manifest.txt');
    if (!fs.existsSync(manifestPath)) {
        throw new Error(`No install manifest found at ${manifestPath} - this project doesn't look like it has the bridge installed.`);
    }

    const lines = fs.readFileSync(manifestPath, 'utf8').split('\n').map((l) => l.trim()).filter(Boolean);
    const [installedVersion, ...files] = lines;
    console.log(`Uninstalling Allure-Katalon Bridge v${installedVersion} from: ${projectPath}`);

    const configFiles = [CONFIG_REL, CATEGORIES_REL];

    for (const relativePath of files) {
        if (configFiles.includes(relativePath) && !removeConfig) {
            console.log(`  KEEP (config; pass --remove-config to delete): ${relativePath}`);
            continue;
        }
        const targetFile = path.join(projectPath, relativePath);
        if (fs.existsSync(targetFile)) {
            fs.unlinkSync(targetFile);
            console.log(`  REMOVED  ${relativePath}`);
        }
    }

    for (const dir of CLEANUP_DIRS) {
        const fullDir = path.join(projectPath, dir);
        if (fs.existsSync(fullDir) && fs.readdirSync(fullDir).length === 0) {
            fs.rmdirSync(fullDir);
            console.log(`  REMOVED  ${dir}/ (now empty)`);
        }
    }

    fs.unlinkSync(manifestPath);
    const manifestDir = path.join(projectPath, '.allure-bridge');
    if (fs.existsSync(manifestDir) && fs.readdirSync(manifestDir).length === 0) {
        fs.rmdirSync(manifestDir);
    }

    console.log('');
    console.log('Uninstall complete.');
    console.log('Note: allure-results/ (generated test output) was left in place - delete it manually if you want it gone too.');
}

function parseArgs(argv) {
    const [command, ...rest] = argv;
    const flags = new Set();
    const positional = [];
    for (const arg of rest) {
        if (arg.startsWith('--')) {
            flags.add(arg);
        } else {
            positional.push(arg);
        }
    }
    return { command, projectPath: positional[0], flags };
}

function main() {
    const { command, projectPath, flags } = parseArgs(process.argv.slice(2));

    if (!command || command === '--help' || command === '-h') {
        usage();
        process.exit(command ? 0 : 1);
        return;
    }

    if (command !== 'install' && command !== 'uninstall') {
        console.error(`Unknown command: ${command}\n`);
        usage();
        process.exit(1);
        return;
    }

    if (!projectPath) {
        console.error('Missing <projectPath>.\n');
        usage();
        process.exit(1);
        return;
    }

    try {
        if (command === 'install') {
            install(projectPath, flags.has('--force'));
        } else {
            uninstall(projectPath, flags.has('--remove-config'));
        }
    } catch (err) {
        console.error(`Error: ${err.message}`);
        process.exit(1);
    }
}

main();
