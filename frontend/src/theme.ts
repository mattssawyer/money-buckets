import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import { gray } from './palette'

export default definePreset(Aura, {
  primitive: {
    borderRadius: { sm: '6px', md: '8px', lg: '10px', xl: '12px' },
  },
  semantic: {
    typography: { fontFamily: 'var(--app-font)', fontSize: '0.875rem' },
    focusRing: { width: '2px', offset: '2px', color: '{text.color}' },
    content: { borderColor: 'var(--app-divider)' },
    primary: {
      ...gray,
      color: '{primary.900}',
      contrastColor: '#ffffff',
      hoverColor: '{primary.800}',
      activeColor: '{primary.700}',
    },
    surface: gray,
    text: {
      color: gray[900],
      hoverColor: gray[950],
      mutedColor: gray[500],
      hoverMutedColor: gray[600],
    },
  },
  components: {
    button: {
      root: {
        paddingX: '0.75rem',
        paddingY: '0.5rem',
        borderRadius: 'var(--app-radius-control)',
        gap: '0.375rem',
        label: { fontWeight: '500' },
        lg: { fontSize: '0.9375rem', paddingX: '1rem', paddingY: '0.6875rem' },
        secondary: {
          background: '{surface.0}',
          hoverBackground: '{surface.50}',
          activeBackground: '{surface.100}',
          borderColor: 'var(--app-control-border)',
          hoverBorderColor: 'var(--app-control-border)',
          activeBorderColor: 'var(--app-control-border)',
          color: '{text.color}',
          hoverColor: '{text.color}',
          activeColor: '{text.color}',
        },
      },
      outlined: {
        secondary: {
          borderColor: 'var(--app-control-border)',
          color: '{text.color}',
          hoverBackground: '{surface.50}',
          activeBackground: '{surface.100}',
        },
      },
    },
    message: {
      root: { borderRadius: 'var(--app-radius-control)' },
      content: { padding: '0.625rem 0.75rem' },
      text: { fontWeight: '450' },
      error: {
        background: 'var(--app-danger-surface)',
        borderColor: 'var(--app-danger-border)',
        color: 'var(--app-danger)',
        shadow: 'none',
      },
      secondary: {
        background: '{surface.50}',
        borderColor: '{surface.200}',
        color: '{surface.600}',
        shadow: 'none',
      },
    },
    skeleton: {
      root: {
        borderRadius: 'var(--app-radius-chip)',
        background: '{surface.100}',
        animationBackground: 'rgb(255 255 255 / 60%)',
      },
    },
    divider: {
      horizontal: { margin: '0.25rem 0' },
      content: { background: '{surface.0}' },
    },
    popover: {
      root: {
        background: '{surface.0}',
        borderColor: 'var(--app-control-border)',
        borderRadius: 'var(--app-radius-control)',
        shadow: 'var(--app-shadow-border-sm)',
      },
      content: { padding: '0.65rem 0.75rem' },
    },
  },
})
