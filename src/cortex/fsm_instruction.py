import shlex
from dataclasses import dataclass
from typing import Iterable, List, Sequence, Set


class InstructionError(ValueError):
    pass


@dataclass(frozen=True)
class Instruction:
    op: str
    args: List[str]
    raw: str


_ARG_COUNTS = {
    "SET_APP": (1, 1),
    "ROUTE": (2, 2),
    "TAP": (2, 2),
    "SWIPE": (5, 5),
    "INPUT": (1, 1),
    "WAIT": (1, 1),
    "BACK": (0, 0),
    "CHECK_POPUP": (0, 0),
    "ASSERT_PAGE": (1, 1),
    "RESTART_APP": (0, 0),
    "DONE": (0, 0),
    "FAIL": (1, 9999),
}


def parse_instructions(text: str, max_commands: int = 3) -> List[Instruction]:
    lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
    if not lines:
        raise InstructionError("empty instruction output")
    if len(lines) > max_commands:
        raise InstructionError(f"too many instructions: {len(lines)} > {max_commands}")

    out: List[Instruction] = []
    for line in lines:
        try:
            parts = shlex.split(line, posix=True)
        except ValueError as e:
            raise InstructionError(f"invalid instruction quoting: {line}") from e
        if not parts:
            continue
        op = parts[0].strip().upper()
        args = parts[1:]
        _validate_arity(op, args)
        out.append(Instruction(op=op, args=args, raw=line))

    if not out:
        raise InstructionError("no valid instructions parsed")
    return out


def validate_allowed(instructions: Sequence[Instruction], allowed_ops: Iterable[str]) -> None:
    allowed: Set[str] = {op.upper() for op in allowed_ops}
    for ins in instructions:
        if ins.op not in allowed:
            raise InstructionError(f"op not allowed in this state: {ins.op}")


def _validate_arity(op: str, args: List[str]) -> None:
    if op not in _ARG_COUNTS:
        raise InstructionError(f"unknown instruction op: {op}")
    min_args, max_args = _ARG_COUNTS[op]
    n = len(args)
    if n < min_args or n > max_args:
        expected = str(min_args) if min_args == max_args else f"{min_args}..{max_args}"
        raise InstructionError(f"{op} expects {expected} args, got {n}")
