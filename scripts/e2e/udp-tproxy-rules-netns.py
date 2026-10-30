#!/usr/bin/env python3
"""Apply the actual deployment script repeatedly inside an isolated router namespace."""
from pathlib import Path
import os
import re
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[2]
    namespace = 'hp-udp-rules-' + str(os.getpid())
    subprocess.run(['ip', 'netns', 'add', namespace], check=True)
    try:
        def run(*args, **kwargs):
            return subprocess.run(['ip', 'netns', 'exec', namespace, *args], check=True, **kwargs)
        run('ip', 'link', 'add', 'lan0', 'type', 'dummy')
        run('ip', 'link', 'add', 'wan0', 'type', 'dummy')
        for interface, address in [('lan0', '192.0.2.1/24'), ('wan0', '203.0.113.1/24')]:
            run('ip', 'addr', 'add', address, 'dev', interface)
            run('ip', 'link', 'set', interface, 'up')
        text = (root / 'backend/tproxy_netty.sh').read_text()
        # Change deployment values in a temporary copy, preserving all production rule logic.
        for key, value in {'LAN_IF': '"lan0"', 'WAN_IF': '"wan0"', 'LAN_CIDR': '"192.0.2.0/24"'}.items():
            text = re.sub(r'^' + key + r'=\S+', key + '=' + value, text, flags=re.M)
        with tempfile.TemporaryDirectory(prefix='hp-udp-rules-') as directory:
            script = Path(directory) / 'tproxy.sh'
            script.write_text(text)
            def apply(enabled, quic):
                run('env', 'ENABLE_UDP_TPROXY=' + str(enabled), 'UDP_TPROXY_443=' + str(quic),
                    'bash', str(script), stdout=subprocess.DEVNULL)
                return run('iptables', '-t', 'mangle', '-S', capture_output=True, text=True).stdout
            for quic in (1, 0, 1):
                apply(1, quic)
                rules = apply(1, quic)
                jumps = [line for line in rules.splitlines() if line.startswith('-A PREROUTING') and '-j nas_proxy_udp' in line]
                assert len(jumps) == 1, jumps
                udp = [line for line in rules.splitlines() if line.startswith('-A nas_proxy_udp')]
                assert len(udp) == (2 if quic else 3), udp
                assert '--dport 53 -j RETURN' in udp[0]
                if quic:
                    assert '--dport 443' not in '\n'.join(udp)
                else:
                    assert '--dport 443 -j RETURN' in '\n'.join(udp)
                assert 'TPROXY' in udp[-1]
                print(f'PASS actual deployment script repeated apply: UDP=1 QUIC={quic}')
            for _ in range(2):
                rules = apply(0, 1)
                assert 'nas_proxy_udp' not in rules, rules
            forward = run('iptables', '-S', 'FORWARD', capture_output=True, text=True).stdout
            output = run('iptables', '-S', 'OUTPUT', capture_output=True, text=True).stdout
            assert 'nas_proxy_reject_fake_ip_udp' in forward and 'nas_proxy_reject_quic' in forward
            assert 'nas_proxy_reject_fake_ip_udp' in output
            print('PASS UDP off removes jump/chain and preserves FORWARD/OUTPUT rejection')
            invalid = Path(directory) / 'invalid.sh'
            invalid.write_text(re.sub(r'^ENABLE_TPROXY=1', 'ENABLE_TPROXY=0', text, flags=re.M))
            result = subprocess.run(['ip', 'netns', 'exec', namespace, 'env', 'ENABLE_UDP_TPROXY=1',
                                     'bash', str(invalid)], capture_output=True, text=True)
            assert result.returncode != 0 and 'ENABLE_TPROXY=1' in result.stderr
            print('PASS UDP on without TCP TPROXY is rejected before rule changes')
    finally:
        subprocess.run(['ip', 'netns', 'del', namespace], check=True)


if __name__ == '__main__':
    main()
