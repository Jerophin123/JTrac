import type { FieldDefinition, Member } from '../../api/types';
import { Checkbox, Input, Select, TextArea } from '../../ui/Field';

interface CustomFieldInputProps {
  field: FieldDefinition;
  value: unknown;
  onChange: (value: unknown) => void;
  members: Member[];
  error?: string | undefined;
  disabled?: boolean;
  /** Visually hide the label (when a surrounding layout already shows the field name). */
  hideLabel?: boolean;
  /** Called when focus leaves a typed input (used to commit inline edits). */
  onBlur?: () => void;
}

/** Editor for one custom field value, typed by the field definition. */
export function CustomFieldInput({ field, value, onChange, members, error, disabled, hideLabel = false, onBlur }: CustomFieldInputProps) {
  const label = field.name;
  const hint = field.description ?? undefined;
  switch (field.type) {
    case 'LONG_TEXT':
      return (
        <TextArea label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} rows={3} value={typeof value === 'string' ? value : ''}
          onChange={(e) => { onChange(e.target.value || null); }} />
      );
    case 'NUMBER':
      return (
        <Input label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} type="number" step="any" inputMode="decimal"
          value={typeof value === 'number' || typeof value === 'string' ? String(value) : ''}
          onChange={(e) => { onChange(e.target.value === '' ? null : Number(e.target.value)); }} />
      );
    case 'DATE':
      return (
        <Input label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} type="date" value={typeof value === 'string' ? value : ''}
          onChange={(e) => { onChange(e.target.value || null); }} />
      );
    case 'URL':
      return (
        <Input label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} type="url" placeholder="https://"
          value={typeof value === 'string' ? value : ''} onChange={(e) => { onChange(e.target.value || null); }} />
      );
    case 'CHECKBOX':
      return <Checkbox label={label} hint={hint} disabled={disabled} checked={value === true} onChange={(e) => { onChange(e.target.checked); }} />;
    case 'SELECT':
      return (
        <Select label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} value={typeof value === 'string' ? value : ''}
          onChange={(e) => { onChange(e.target.value || null); }}>
          <option value="">None</option>
          {field.options.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </Select>
      );
    case 'MULTI_SELECT': {
      const selected = Array.isArray(value) ? (value as string[]) : [];
      return (
        <fieldset className="field-group" disabled={disabled}>
          <legend className={hideLabel ? 'visually-hidden' : undefined}>{label}</legend>
          {field.options.map((option) => (
            <Checkbox
              key={option.id}
              label={option.label}
              checked={selected.includes(option.id)}
              onChange={(e) => {
                const next = e.target.checked ? [...selected, option.id] : selected.filter((id) => id !== option.id);
                onChange(next.length ? next : null);
              }}
            />
          ))}
          {error && <p className="field-error">{error}</p>}
        </fieldset>
      );
    }
    case 'USER':
      return (
        <Select label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} value={typeof value === 'string' ? value : ''}
          onChange={(e) => { onChange(e.target.value || null); }}>
          <option value="">Nobody</option>
          {members.map((member) => (
            <option key={member.userId} value={member.userId}>
              {member.displayName} (@{member.handle})
            </option>
          ))}
        </Select>
      );
    default:
      return (
        <Input label={label} hideLabel={hideLabel} onBlur={onBlur} hint={hint} error={error} disabled={disabled} value={typeof value === 'string' ? value : ''}
          onChange={(e) => { onChange(e.target.value || null); }} />
      );
  }
}

/** Read-only rendering of a custom value. */
export function formatCustomValue(field: FieldDefinition, value: unknown, members: Member[]): string {
  if (value === null || value === undefined || value === '') {
    return 'None';
  }
  switch (field.type) {
    case 'SELECT':
      return field.options.find((o) => o.id === value)?.label ?? 'None';
    case 'MULTI_SELECT':
      return Array.isArray(value)
        ? (value as string[]).map((id) => field.options.find((o) => o.id === id)?.label ?? id).join(', ')
        : 'None';
    case 'CHECKBOX':
      return value === true ? 'Yes' : 'No';
    case 'USER':
      return members.find((m) => m.userId === value)?.displayName ?? 'Former member';
    default:
      return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? String(value) : JSON.stringify(value);
  }
}
