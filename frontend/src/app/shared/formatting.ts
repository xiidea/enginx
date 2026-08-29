import { Pipe, PipeTransform } from '@angular/core';

/**
 * "in 3 days", "5 minutes ago".
 *
 * An operator reading an expiry column cares about how long is left, not about a timestamp they
 * have to subtract in their head. The absolute value stays available as a tooltip.
 */
@Pipe({ name: 'relative', standalone: true })
export class RelativePipe implements PipeTransform {
  transform(value: string | number | null | undefined): string {
    if (value === null || value === undefined) {
      return '—';
    }
    const seconds =
      typeof value === 'number' ? value : Math.round((new Date(value).getTime() - Date.now()) / 1000);

    return formatRelativeSeconds(seconds);
  }
}

export function formatRelativeSeconds(seconds: number): string {
  const absolute = Math.abs(seconds);
  const units: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, 'second'],
    [3600, 'minute'],
    [86400, 'hour'],
    [2592000, 'day'],
    [31536000, 'month'],
    [Number.MAX_SAFE_INTEGER, 'year'],
  ];

  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  let previous = 1;
  for (const [limit, unit] of units) {
    if (absolute < limit) {
      return formatter.format(Math.round(seconds / previous), unit);
    }
    previous = limit;
  }
  return formatter.format(Math.round(seconds / 31536000), 'year');
}

@Pipe({ name: 'datetime', standalone: true })
export class DateTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }
    return new Date(value).toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

@Pipe({ name: 'bytes', standalone: true })
export class BytesPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value === null || value === undefined) {
      return '—';
    }
    const units = ['B', 'KB', 'MB', 'GB'];
    let size = value;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }
    return `${size % 1 === 0 ? size : size.toFixed(1)} ${units[unit]}`;
  }
}
