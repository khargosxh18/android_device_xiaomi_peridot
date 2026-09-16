#!/usr/bin/env python3
import json
import sys
import os

def validate_powerhint(filepath):
    if not os.path.exists(filepath):
        print(f"Error: File not found: {filepath}", file=sys.stderr)
        sys.exit(1)

    try:
        with open(filepath, 'r') as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        print(f"Error: Invalid JSON syntax in {filepath}:\n{e}", file=sys.stderr)
        sys.exit(1)

    errors = 0
    warnings = 0

    nodes = data.get('Nodes', [])
    actions = data.get('Actions', [])
    adpf_configs = data.get('AdpfConfig', [])

    print(f"--- Validating: {filepath} ---")

    # 1. Validate ADPF Configs
    adpf_names = [cfg['Name'] for cfg in adpf_configs if 'Name' in cfg]
    adpf_set = set(adpf_names)
    adpf_dupes = set(name for name in adpf_names if adpf_names.count(name) > 1)
    
    if adpf_dupes:
        print(f"[!] ERROR: Duplicate ADPF Config names: {adpf_dupes}")
        errors += 1
    else:
        print(f"[*] ADPF Configs found: {len(adpf_configs)}")

    adpf_required_fields = [
        'PID_Po', 'PID_Pu', 'PID_I', 'PID_I_Init', 'PID_I_High', 'PID_I_Low',
        'PID_Do', 'PID_Du', 'UclampMin_Init', 'UclampMin_High', 'UclampMin_Low',
        'SamplingWindow_P', 'SamplingWindow_I', 'SamplingWindow_D',
        'StaleTimeFactor', 'ReportingRateLimitNs', 'TargetTimeFactor'
    ]

    for cfg in adpf_configs:
        name = cfg.get('Name', '<unnamed>')
        missing = [k for k in adpf_required_fields if k not in cfg]
        if missing:
            print(f"[!] WARNING: ADPF Config '{name}' missing fields: {missing}")
            warnings += 1
        if cfg.get('UclampMin_High', 0) < cfg.get('UclampMin_Low', 0):
            print(f"[!] ERROR: ADPF Config '{name}': UclampMin_High < UclampMin_Low")
            errors += 1
        if cfg.get('PID_I_High', 0) < cfg.get('PID_I_Low', 0):
            print(f"[!] ERROR: ADPF Config '{name}': PID_I_High < PID_I_Low")
            errors += 1

    # 2. Validate Nodes
    node_names = [n['Name'] for n in nodes if 'Name' in n]
    node_set = set(node_names)
    node_map = {n['Name']: n for n in nodes}

    node_dupes = set(n for n in node_names if node_names.count(n) > 1)
    if node_dupes:
        print(f"[!] ERROR: Duplicate Node names: {node_dupes}")
        errors += 1

    for n in nodes:
        name = n.get('Name', '<unnamed>')
        values = n.get('Values', [])
        default_idx = n.get('DefaultIndex')

        if 'Path' not in n and 'Paths' not in n:
            print(f"[!] ERROR: Node '{name}' is missing Path/Paths")
            errors += 1

        if default_idx is not None and not (0 <= default_idx < len(values)):
            print(f"[!] ERROR: Bad DefaultIndex in node '{name}': {default_idx} (Values length: {len(values)})")
            errors += 1

        if n.get('ResetOnInit') and default_idx is None:
            print(f"[!] WARNING: Node '{name}' has ResetOnInit=true but no DefaultIndex set")
            warnings += 1

        # Check ADPF Event Node mappings
        paths = n.get('Paths', [])
        if any(p.startswith('<AdpfConfig>:') for p in paths):
            for val in values:
                if val not in adpf_set:
                    print(f"[!] ERROR: ADPF Event Node '{name}' references undefined ADPF Profile: '{val}'")
                    errors += 1

    # 3. Validate Actions
    valid_action_types = {None, 'EndHint', 'MaskHint', 'DoHint'}

    for i, a in enumerate(actions):
        action_type = a.get('Type')
        hint_name = a.get('PowerHint', '<unknown>')
        node_ref = a.get('Node')
        target_val = a.get('Value')

        if action_type not in valid_action_types:
            print(f"[!] ERROR: Action {i} [{hint_name}] has invalid Type: '{action_type}'")
            errors += 1

        if action_type in ('EndHint', 'MaskHint'):
            if not target_val:
                print(f"[!] ERROR: Action {i} [{hint_name}]: '{action_type}' missing target Value")
                errors += 1
            continue

        if node_ref not in node_set:
            print(f"[!] ERROR: Action {i} [{hint_name}] references non-existent Node: '{node_ref}'")
            errors += 1
            continue

        node = node_map.get(node_ref)
        if node and target_val not in node.get('Values', []):
            print(f"[!] ERROR: Action {i} [{hint_name}]: Node '{node_ref}' assigned value '{target_val}' not in allowed: {node.get('Values')}")
            errors += 1

    print("--- Validation Summary ---")
    print(f"Errors: {errors}, Warnings: {warnings}")

    if errors > 0:
        sys.exit(1)
    else:
        print("[✓] Configuration is fully valid for libperfmgr.")
        sys.exit(0)

if __name__ == '__main__':
    target_file = sys.argv[1] if len(sys.argv) > 1 else 'powerhint.json'
    validate_powerhint(target_file)
