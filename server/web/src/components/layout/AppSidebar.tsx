import { useState } from 'react'
import { NavLink, useMatch, useNavigate } from 'react-router-dom'
import { LogOutIcon } from 'lucide-react'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from '@/components/ui/sidebar'
import { logout } from '@/lib/api'
import { navGroups, type NavItem } from './nav-items'

function SidebarNavLink({ item }: { item: NavItem }) {
  const match = useMatch({ path: item.to, end: item.end ?? false })
  return (
    <SidebarMenuButton asChild isActive={!!match} tooltip={item.label}>
      <NavLink to={item.to} end={item.end}>
        <item.icon />
        <span>{item.label}</span>
      </NavLink>
    </SidebarMenuButton>
  )
}

export default function AppSidebar() {
  const navigate = useNavigate()
  const [loggingOut, setLoggingOut] = useState(false)

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await logout()
    } catch {
      // Even if the request fails, drop the user back at the login screen.
    } finally {
      navigate('/login', { replace: true })
    }
  }

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <div className="flex items-center gap-2 px-2 py-1.5">
          <span className="truncate text-lg font-semibold tracking-tight text-umber-800 group-data-[collapsible=icon]:hidden dark:text-umber-200">
            Umber
          </span>
        </div>
      </SidebarHeader>
      <SidebarContent>
        {navGroups.map((group) => (
          <SidebarGroup key={group.label}>
            <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
                  <SidebarMenuItem key={item.to}>
                    <SidebarNavLink item={item} />
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton onClick={handleLogout} disabled={loggingOut} tooltip="Log out">
              <LogOutIcon />
              <span>{loggingOut ? 'Logging out…' : 'Log out'}</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  )
}
