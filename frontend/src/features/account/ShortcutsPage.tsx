import { useEffect, useRef, useState } from 'react';
import {
  bindingProblem,
  chordFromEvent,
  conflictingShortcut,
  isSingleKeyBinding,
  SEQUENCE_TIMEOUT_MS,
  SHORTCUT_GROUPS,
  SHORTCUTS,
  useShortcutSettings,
  type ShortcutDefinition,
  type ShortcutId,
} from '../../lib/shortcuts';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { Kbd } from '../../ui/Kbd';
import { SettingRow, SettingsCard, SettingsList, SettingsListItem, SettingsSection } from '../../ui/Settings';
import { Switch } from '../../ui/Switch';
import { useToast } from '../../ui/Toast';
import { AccountLayout } from './AccountPages';
import styles from './ShortcutsPage.module.css';

/** Account › Keyboard shortcuts: change, remove or reset the keys for every shortcut on this device. */
export function ShortcutsPage() {
  const shortcuts = useShortcutSettings();
  const { settings, setSingleKeyEnabled, resetAll } = shortcuts;
  const [editing, setEditing] = useState<ShortcutId | null>(null);
  const toast = useToast();
  const anyCustomized = Object.keys(settings.bindings).length > 0;

  return (
    <AccountLayout>
      <SettingsSection
        title="Keyboard shortcuts"
        description="Change the keys for any shortcut, or turn single-key shortcuts off. Saved on this device only. Press ? anywhere to see them all."
      >
        <SettingsCard>
          <SettingRow
            label="Single-key shortcuts"
            description="Shortcuts without Ctrl, ⌘ or Alt, such as C or G then I. Turn them off if you use speech input or press keys by accident."
          >
            <Switch
              label="Single-key shortcuts"
              hideLabel
              checked={settings.singleKeyEnabled}
              onChange={(event) => {
                setSingleKeyEnabled(event.target.checked);
              }}
            />
          </SettingRow>
        </SettingsCard>
      </SettingsSection>

      {SHORTCUT_GROUPS.map((group) => (
        <SettingsSection
          key={group}
          title={group}
          layout="stacked"
          actions={
            group === SHORTCUT_GROUPS[0] && anyCustomized ? (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  resetAll();
                  setEditing(null);
                  toast.success('All shortcuts are back to their default keys.');
                }}
              >
                Reset all to defaults
              </Button>
            ) : undefined
          }
        >
          <SettingsCard flush>
            <SettingsList label={`${group} shortcuts`}>
              {SHORTCUTS.filter((s) => s.group === group).map((shortcut) => (
                <SettingsListItem
                  key={shortcut.id}
                  title={shortcut.label}
                  meta={shortcut.scope === 'org' ? 'Inside an organization' : 'Everywhere'}
                >
                  {editing === shortcut.id ? (
                    <ShortcutRecorder
                      shortcut={shortcut}
                      onDone={() => {
                        setEditing(null);
                      }}
                    />
                  ) : (
                    <ShortcutValue
                      shortcut={shortcut}
                      onChange={() => {
                        setEditing(shortcut.id);
                      }}
                    />
                  )}
                </SettingsListItem>
              ))}
            </SettingsList>
          </SettingsCard>
        </SettingsSection>
      ))}
    </AccountLayout>
  );
}

function ShortcutValue({ shortcut, onChange }: { shortcut: ShortcutDefinition; onChange: () => void }) {
  const { settings, bindingFor, isCustomized, resetBinding } = useShortcutSettings();
  const id = shortcut.id as ShortcutId;
  const binding = bindingFor(id);
  const switchedOff = binding !== null && !settings.singleKeyEnabled && isSingleKeyBinding(binding);
  return (
    <>
      <Kbd binding={binding} className={switchedOff ? styles.inactive : undefined} />
      {switchedOff && <span className={styles.note}>(single-key shortcuts are off)</span>}
      <Button variant="secondary" size="sm" onClick={onChange} aria-label={`Change shortcut for ${shortcut.label}`}>
        Change
      </Button>
      {isCustomized(id) && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            resetBinding(id);
          }}
          aria-label={`Reset shortcut for ${shortcut.label}`}
        >
          Reset
        </Button>
      )}
    </>
  );
}

/**
 * Captures new keys: a chord, or two plain keys pressed within a second for a sequence. Tab
 * still moves focus and Escape cancels, so the recorder never traps the keyboard.
 */
function ShortcutRecorder({ shortcut, onDone }: { shortcut: ShortcutDefinition; onDone: () => void }) {
  const { settings, setBinding } = useShortcutSettings();
  const id = shortcut.id as ShortcutId;
  const [chords, setChords] = useState<string[]>([]);
  const lastPress = useRef(0);
  const field = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    field.current?.focus();
  }, []);

  const binding = chords.length > 0 ? chords.join(' ') : null;
  const problem = binding ? bindingProblem(binding) : null;
  const conflict = binding && !problem ? conflictingShortcut(settings, binding, id) : undefined;
  const willBeInactive = binding !== null && !problem && !settings.singleKeyEnabled && isSingleKeyBinding(binding);

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Tab') {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (event.key === 'Escape') {
      onDone();
      return;
    }
    const chord = chordFromEvent(event);
    if (!chord) {
      return;
    }
    const now = Date.now();
    const first: string | undefined = chords.length === 1 ? chords[0] : undefined;
    const sequence = first && isSingleKeyBinding(first) && isSingleKeyBinding(chord) && now - lastPress.current < SEQUENCE_TIMEOUT_MS;
    lastPress.current = now;
    setChords(sequence ? [first, chord] : [chord]);
  };

  return (
    <div className={styles.recorder}>
      {/* A focusable button that listens for keys (Enter and Space are captured too, not clicked). */}
      <button
        ref={field}
        type="button"
        className={styles.capture}
        aria-label={`New keys for ${shortcut.label}`}
        aria-describedby={`recorder-hint-${shortcut.id}`}
        data-shortcut-recorder=""
        onKeyDown={onKeyDown}
      >
        {binding ? <Kbd binding={binding} /> : <span className={styles.placeholder}>Press the new keys…</span>}
      </button>
      <p id={`recorder-hint-${shortcut.id}`} className={styles.hint}>
        For a sequence, press two keys within a second (for example G then I). Esc cancels.
      </p>
      <div className={styles.feedback} aria-live="polite">
        {problem && <Alert tone="danger">{problem}</Alert>}
        {conflict && (
          <Alert tone="warning">
            Already used by “{conflict.label}”. Saving gives these keys to “{shortcut.label}” and turns the other shortcut off.
          </Alert>
        )}
        {willBeInactive && <Alert tone="info">Single-key shortcuts are off, so these keys won’t work until you turn them on.</Alert>}
      </div>
      <div className={styles.actions}>
        <Button
          variant="primary"
          size="sm"
          disabled={!binding || Boolean(problem)}
          onClick={() => {
            if (binding) {
              setBinding(id, binding);
              onDone();
            }
          }}
        >
          {conflict ? 'Use these keys' : 'Save'}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            setBinding(id, null);
            onDone();
          }}
        >
          Remove shortcut
        </Button>
        <Button variant="ghost" size="sm" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </div>
  );
}
